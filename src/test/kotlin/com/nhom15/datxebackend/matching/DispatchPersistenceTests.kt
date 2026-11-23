package com.nhom15.datxebackend.matching

import com.nhom15.datxebackend.ride.repository.*
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.matching.service.OfferReleaseService
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.data.redis.core.StringRedisTemplate
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.Properties
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Explicit opt-in to a disposable local database. Every test uses its own schema. */
@EnabledIfEnvironmentVariable(named="TEST_DB_URL", matches=".+")
class DispatchPersistenceTests {
    private lateinit var connection: Connection
    private lateinit var jdbc: JdbcTemplate
    private lateinit var schema: String
    private fun open(): Connection = DriverManager.getConnection(System.getenv("TEST_DB_URL"),System.getenv("TEST_DB_USER"),System.getenv("TEST_DB_PASSWORD"))

    @BeforeEach fun setup() {
        connection=open();jdbc=JdbcTemplate(SingleConnectionDataSource(connection,true))
        schema="dispatch_test_"+UUID.randomUUID().toString().replace("-","")
        jdbc.execute("CREATE SCHEMA $schema");jdbc.execute("SET search_path TO $schema")
        Files.list(Path.of("src/main/resources/db/migration")).use { files ->
            files.filter { it.toString().endsWith(".sql") }.sorted(compareBy { it.fileName.toString().substringBefore("__").drop(1).toInt() })
                .forEach { jdbc.execute(Files.readString(it).removePrefix("\uFEFF")) }
        }
        jdbc.update("INSERT INTO customers(id,firebase_uid) VALUES(1,'customer1'),(2,'customer2')")
        jdbc.update("INSERT INTO drivers(id,firebase_uid,phone_number,status) VALUES(7,'driver7','0900000007','ONLINE'),(8,'driver8','0900000008','ONLINE')")
        jdbc.update("INSERT INTO vehicles(id,customer_id,plate_number) VALUES(2,1,'TEST-02'),(3,2,'TEST-03')")
        jdbc.update("""INSERT INTO rides(id,customer_id,vehicle_id,type,status,fare_estimate,payment_method,transmission,vehicle_type,search_expires_at)
            VALUES(42,1,2,'INSTANT','SEARCHING',30000,'CASH','AUTO','CAR',CURRENT_TIMESTAMP+INTERVAL '90 seconds'),
                  (43,2,3,'INSTANT','SEARCHING',30000,'CASH','AUTO','CAR',CURRENT_TIMESTAMP+INTERVAL '90 seconds')""")
    }
    @AfterEach fun cleanup() { if(::connection.isInitialized) { jdbc.execute("DROP SCHEMA $schema CASCADE");connection.close() } }

    @Test fun `redispatch releases the assignment without reusing the previous driver's thread`() {
        jdbc.update("UPDATE rides SET driver_id=7, status='DRIVER_ASSIGNED' WHERE id=42")
        jdbc.update("INSERT INTO conversations(ride_id,customer_id,driver_id) VALUES(42,1,7)")
        val firstThread = jdbc.queryForObject("SELECT id FROM conversations WHERE ride_id=42 AND driver_id=7", Long::class.java)!!
        jdbc.update(
            "INSERT INTO chat_messages(conversation_id,sender_role,sender_driver_id,content) VALUES(?,'DRIVER',7,?)",
            firstThread, "Tôi đang đến điểm đón",
        )

        assertEquals(1, jdbc.update("UPDATE rides SET driver_id=NULL, status='SEARCHING' WHERE id=42"))
        jdbc.update("UPDATE rides SET driver_id=8, status='DRIVER_ASSIGNED' WHERE id=42")
        jdbc.update("INSERT INTO conversations(ride_id,customer_id,driver_id) VALUES(42,1,8) ON CONFLICT (ride_id,driver_id) DO NOTHING")

        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM conversations WHERE ride_id=42", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM chat_messages WHERE conversation_id=?", Int::class.java, firstThread))
        assertEquals(0, jdbc.queryForObject(
            "SELECT count(*) FROM chat_messages m JOIN conversations c ON c.id=m.conversation_id WHERE c.ride_id=42 AND c.driver_id=8",
            Int::class.java,
        ))
    }

    @Test fun `queue schedule during execution survives stale acknowledgement and stale retry`() {
        val queue=RideDispatchOutboxRepository(jdbc)
        queue.enqueue(42);val old=queue.claimDue().single()
        assertTrue(queue.lockCurrent(old))
        queue.schedule(42);assertFalse(queue.lockCurrent(old));queue.finish(old)
        val next=queue.claimDue().single()
        assertTrue(next.revision>old.revision)
        queue.finish(old,Duration.ZERO,"stale retry")
        queue.finish(next,Duration.ofSeconds(3))
        assertTrue(queue.claimDue().isEmpty())
        jdbc.update("UPDATE ride_dispatch_outbox SET available_at=CURRENT_TIMESTAMP-INTERVAL '1 second'")
        val delayed=queue.claimDue().single();queue.finish(delayed)
        assertTrue(queue.claimDue().isEmpty())
    }

    @Test fun `database ledger prevents two outstanding offers per ride or driver and preserves rejected history`() {
        val offers=DispatchOfferRepository(jdbc)
        val a=DispatchOffer(UUID.randomUUID().toString(),42,7,Instant.now().plusSeconds(12))
        assertTrue(offers.record(a))
        assertFalse(offers.record(a.copy(offerId=UUID.randomUUID().toString(),rideId=43)))
        assertFalse(offers.record(a.copy(offerId=UUID.randomUUID().toString(),driverId=8)))
        assertFalse(offers.close(a.offerId,"REJECTED",8,42))
        assertTrue(offers.close(a.offerId,"REJECTED",7,42))
        assertTrue(offers.record(a.copy(offerId=UUID.randomUUID().toString(),driverId=8)))
        assertEquals(setOf("7","8"),offers.attemptedDrivers(42))
        assertFalse(offers.record(a.copy(offerId=UUID.randomUUID().toString(),rideId=43,expiresAt=Instant.now().minusSeconds(1))))
    }

    @Test fun `customer cancel and accept conditional updates have exactly one database winner`() {
        val start=CountDownLatch(1)
        val executor=Executors.newFixedThreadPool(2)
        try {
            val futures=listOf("CANCELLED","DRIVER_ASSIGNED").map { status -> executor.submit<Int> {
                open().use { c -> c.createStatement().use { it.execute("SET search_path TO $schema") }
                    start.await();c.prepareStatement("UPDATE rides SET status=? WHERE id=42 AND status='SEARCHING'").use {
                        it.setString(1,status);it.executeUpdate()
                    }
                }
            } }
            start.countDown();assertEquals(1,futures.sumOf { it.get() })
        } finally { executor.shutdownNow() }
    }

    @Test fun `a concurrent offer release waits for and preserves the assigned ride and accepted offer`() {
        val dataSource = DriverManagerDataSource(
            System.getenv("TEST_DB_URL"), System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"),
        ).apply {
            connectionProperties = Properties().apply { setProperty("currentSchema", this@DispatchPersistenceTests.schema) }
        }
        val releaseJdbc = JdbcTemplate(dataSource)
        val rides = mock(RideRepository::class.java)
        `when`(rides.findByIdForUpdate(42)).thenAnswer {
            val status = releaseJdbc.queryForObject("SELECT status FROM rides WHERE id=42 FOR UPDATE", String::class.java)!!
            Optional.of(Ride(
                customerId = 1, vehicleId = 2, status = RideStatus.valueOf(status), type = RideType.INSTANT,
                pickup = Location(GeoPoint(21.03, 105.84), "Đón"), dropoff = Location(GeoPoint(21.04, 105.85), "Trả"),
                vehicleType = VehicleType.CAR, transmission = Transmission.AUTO, fareEstimate = 30_000,
                paymentMethod = PaymentMethod.CASH,
            ))
        }
        val redis = mock(StringRedisTemplate::class.java)
        val service = OfferReleaseService(
            redis, MatchingProperties(), DispatchOfferRepository(releaseJdbc),
            mock(NotificationPort::class.java), rides, JdbcTransactionManager(dataSource),
        )
        val offer = DispatchOffer(UUID.randomUUID().toString(), 42, 7, Instant.now().plusSeconds(30))
        val offers = DispatchOfferRepository(jdbc)
        assertTrue(offers.record(offer))

        val worker = Executors.newSingleThreadExecutor()
        try {
            connection.autoCommit = false
            jdbc.update("UPDATE rides SET driver_id=7, status='DRIVER_ASSIGNED' WHERE id=42")
            val started = CountDownLatch(1)
            val result = worker.submit<Boolean> {
                started.countDown()
                service.release(42, 7, "rejected", offer.offerId)
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            try {
                assertThrows(TimeoutException::class.java) { result.get(500, TimeUnit.MILLISECONDS) }
                assertTrue(offers.close(offer.offerId, "ACCEPTED"))
                connection.commit()
                assertFalse(result.get(5, TimeUnit.SECONDS))
                assertFalse(service.release(42, 7, "rejected", offer.offerId))
                assertTrue(offers.accepted(offer.offerId, 42, 7, 0))
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
        } finally {
            worker.shutdownNow()
        }
    }

    @Test fun `reject reconciles an interrupted reservation and can release an orphan without a database offer`() {
        val redisPort = System.getenv("TEST_REDIS_PORT")?.toIntOrNull()
        assumeTrue(redisPort != null && redisPort != 6379, "Requires disposable Redis 7")
        val factory = LettuceConnectionFactory(RedisStandaloneConfiguration("127.0.0.1", redisPort!!).apply { database = 12 })
        factory.afterPropertiesSet()
        try {
            val redis = StringRedisTemplate(factory).apply { afterPropertiesSet() }
            val rides = mock(RideRepository::class.java)
            val searching = Ride(
                customerId = 1, vehicleId = 2, status = RideStatus.SEARCHING, type = RideType.INSTANT,
                pickup = Location(GeoPoint(21.03, 105.84), "Đón"), dropoff = Location(GeoPoint(21.04, 105.85), "Trả"),
                vehicleType = VehicleType.CAR, transmission = Transmission.AUTO, fareEstimate = 30_000,
                paymentMethod = PaymentMethod.CASH,
            )
            `when`(rides.findByIdForUpdate(42)).thenReturn(Optional.of(searching))
            `when`(rides.findByIdForUpdate(43)).thenReturn(Optional.of(searching))
            val offers = DispatchOfferRepository(jdbc)
            val service = OfferReleaseService(
                redis, MatchingProperties(), offers, mock(NotificationPort::class.java),
                rides, JdbcTransactionManager(jdbc.dataSource!!),
            )
            val token = UUID.randomUUID().toString()
            assertTrue(offers.record(DispatchOffer(token, 42, 7, Instant.now().plusSeconds(30))))
            val state = RedisKeys.rideState("42")
            redis.opsForHash<String, String>().putAll(state, mapOf(
                "status" to "DRIVER_ASSIGNED", "driverId" to "7", "currentOfferId" to token,
            ))
            redis.opsForValue().set(RedisKeys.driverCurrentRide("7"), "42")
            redis.opsForValue().set(RedisKeys.driverStatus("7"), "BUSY")
            redis.opsForValue().set(RedisKeys.driverAlive("7"), "1", Duration.ofSeconds(30))

            assertFalse(service.release(42, 7, "rejected", "old-token"))
            assertEquals("42", redis.opsForValue().get(RedisKeys.driverCurrentRide("7")))
            assertTrue(service.release(42, 7, "rejected", token))
            assertEquals("SEARCHING", redis.opsForHash<String, String>().get(state, "status"))
            assertNull(redis.opsForValue().get(RedisKeys.driverCurrentRide("7")))
            assertEquals("ONLINE", redis.opsForValue().get(RedisKeys.driverStatus("7")))
            assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT status FROM ride_dispatch_offers WHERE offer_id=?", String::class.java, token,
            ))
            assertFalse(service.release(42, 7, "rejected", token))

            val orphanState = RedisKeys.rideState("43")
            val orphanToken = UUID.randomUUID().toString()
            redis.opsForHash<String, String>().putAll(orphanState, mapOf(
                "status" to "SEARCHING", "currentDriver" to "8", "currentOfferId" to orphanToken,
            ))
            redis.opsForValue().set(RedisKeys.driverLock("8"), "43")
            assertTrue(service.release(43, 8, "ignored", orphanToken))
            assertNull(redis.opsForValue().get(RedisKeys.driverLock("8")))
            assertNull(offers.outstanding(43))
        } finally {
            factory.destroy()
        }
    }

    @Test fun `actual assignment SQL refuses expired offer and expired search and succeeds once`() {
        val offers=DispatchOfferRepository(jdbc)
        val a=DispatchOffer(UUID.randomUUID().toString(),42,7,Instant.now().plusSeconds(12))
        assertTrue(offers.record(a))
        val query=RideRepository::class.java.getMethod("assignDriverIfSearching",Long::class.javaPrimitiveType,Long::class.javaPrimitiveType)
            .getAnnotation(org.springframework.data.jpa.repository.Query::class.java).value
            .replace(":driverId","7").replace(":rideId","42")
        jdbc.update("UPDATE ride_dispatch_offers SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second'")
        assertEquals(0,jdbc.update(query))
        jdbc.update("UPDATE ride_dispatch_offers SET expires_at=CURRENT_TIMESTAMP+INTERVAL '12 seconds'")
        jdbc.update("UPDATE rides SET search_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE id=42")
        assertEquals(0,jdbc.update(query))
        jdbc.update("UPDATE rides SET search_expires_at=CURRENT_TIMESTAMP+INTERVAL '90 seconds' WHERE id=42")
        assertEquals(1,jdbc.update(query));assertEquals(0,jdbc.update(query))
    }
    @Test fun `two driver sessions competing for assignment have exactly one winner`() {
        val offers = DispatchOfferRepository(jdbc)
        assertTrue(offers.record(DispatchOffer(UUID.randomUUID().toString(), 42, 7, Instant.now().plusSeconds(60))))
        val query = RideRepository::class.java
            .getMethod("assignDriverIfSearching", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .getAnnotation(org.springframework.data.jpa.repository.Query::class.java).value
            .replace(":rideId", "42")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(7, 8).map { driverId ->
                executor.submit<Int> {
                    open().use { session ->
                        session.createStatement().use { statement ->
                            statement.execute("SET search_path TO $schema")
                            ready.countDown()
                            check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                            statement.executeUpdate(query.replace(":driverId", driverId.toString()))
                        }
                    }
                }
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            assertEquals(listOf(1, 0), futures.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(7L, jdbc.queryForObject("SELECT driver_id FROM rides WHERE id=42", Long::class.java))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `idempotency receipt is stable for five minutes and expired key may be reused`() {
        val receipts = RideCreateIdempotencyRepository(jdbc, com.nhom15.datxebackend.config.properties.MatchingProperties())
        receipts.insert(1, "booking-key", "hash-a", 42)
        assertEquals(42L, receipts.find(1, "booking-key")?.rideId)
        assertThrows(com.nhom15.datxebackend.common.exception.ConflictException::class.java) {
            receipts.insert(1, "booking-key", "hash-b", 43)
        }
        jdbc.update("UPDATE ride_create_idempotency SET created_at=CURRENT_TIMESTAMP-INTERVAL '6 minutes'")
        assertNull(receipts.find(1, "booking-key"))
        // A receipt must reference a ride owned by its customer.
        jdbc.update("UPDATE rides SET status='COMPLETED' WHERE id=42")
        jdbc.update("""INSERT INTO rides(id,customer_id,vehicle_id,type,status,fare_estimate,payment_method,transmission,vehicle_type)
            VALUES(44,1,2,'INSTANT','COMPLETED',30000,'CASH','AUTO','CAR')""")
        receipts.insert(1, "booking-key", "hash-c", 44)
        assertEquals(44L, receipts.find(1, "booking-key")?.rideId)
    }

}
