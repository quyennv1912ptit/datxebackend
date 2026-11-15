package com.nhom15.datxebackend.ride

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.ride.dto.request.CancelRideRequest
import com.nhom15.datxebackend.ride.dto.request.CreateRideRequest
import com.nhom15.datxebackend.ride.dto.response.CreateRideResponse
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.service.RideService
import com.nhom15.datxebackend.ride.service.ScheduledRideScheduler
import com.nhom15.datxebackend.routing.model.PolylineCodec
import com.nhom15.datxebackend.routing.model.SavedRideRoute
import com.nhom15.datxebackend.routing.service.RideRouteService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Answers
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Requires an explicitly configured disposable PostgreSQL instance. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@SpringBootTest(properties = [
    "spring.datasource.username=\${TEST_DB_USER}",
    "spring.datasource.password=\${TEST_DB_PASSWORD:}",
    "spring.data.redis.url=redis://127.0.0.1:1",
    "cloudinary.url=cloudinary://test:test@test",
    "firebase.credentials-location=classpath:unused-test-credentials.json",
    "app.bootstrap.admin.firebase-uid=",
    "app.scheduled-ride.expire-after=10m",
    "app.scheduled-ride.dispatch-lead-time=15m",
])
class ScheduledRideBookingPostgresTests {
    @MockitoBean lateinit var firebaseApp: FirebaseApp
    @MockitoBean lateinit var firebaseAuth: FirebaseAuth
    @MockitoBean lateinit var firebaseMessaging: FirebaseMessaging
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS) lateinit var redis: StringRedisTemplate
    @MockitoBean lateinit var rateLimiter: RedisRateLimiter
    @MockitoBean lateinit var routes: RideRouteService
    @MockitoBean(name = "taskScheduler") lateinit var taskScheduler: ThreadPoolTaskScheduler
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var rides: RideService
    @Autowired lateinit var scheduler: ScheduledRideScheduler

    @Test fun `concurrent booking leaves one schedule and a customer can ride immediately while it waits`() {
        val customerId = jdbc.queryForObject("INSERT INTO customers(firebase_uid) VALUES ('test-customer') RETURNING id", Long::class.java)!!
        val vehicleId = jdbc.queryForObject(
            "INSERT INTO vehicles(customer_id, plate_number, transmission_type) VALUES (?, 'TEST-01', 'AUTO') RETURNING id",
            Long::class.java, customerId,
        )!!
        val actor = AuthUser(customerId, "test-customer", Role.CUSTOMER, null, null, null, "ACTIVE")
        val pickup = GeoPoint(21.03, 105.84)
        val dropoff = GeoPoint(21.04, 105.85)
        val request = CreateRideRequest(
            vehicleId = vehicleId, type = RideType.SCHEDULED, scheduledAt = Instant.now().plusSeconds(7_200),
            pickupAddress = "Đón", pickupLat = pickup.lat, pickupLng = pickup.lng,
            dropoffAddress = "Đến", dropoffLat = dropoff.lat, dropoffLng = dropoff.lng,
        )
        `when`(routes.resolve(customerId, vehicleId, VehicleType.CAR, pickup, dropoff, null)).thenReturn(
            SavedRideRoute("test-route", PolylineCodec.encode(listOf(pickup, dropoff)), 2_000.0, 300.0, "TEST", VehicleType.CAR),
        )
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val results = try {
            val calls = (1..2).map { number ->
                workers.submit<Pair<String, Result<CreateRideResponse>>> {
                    check(start.await(5, TimeUnit.SECONDS))
                    val key = "schedule-$number"
                    key to runCatching { rides.createRide(actor, request, key) }
                }
            }
            start.countDown()
            calls.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
        }
        val successful = results.filter { it.second.isSuccess }
        assertEquals(1, successful.size, results.joinToString { (key, result) -> "$key: $result" })
        val (key, result) = successful.single()
        val scheduled = result.getOrThrow()
        val failure = assertInstanceOf(AppException::class.java, results.single { it.second.isFailure }.second.exceptionOrNull())
        assertEquals(ErrorCode.SCHEDULED_RIDE_EXISTS, failure.errorCode)
        assertEquals(409, failure.httpStatus.value())
        assertEquals(RideStatus.PENDING, scheduled.status)
        assertEquals(scheduled.id, rides.createRide(actor, request, key).id)

        val instant = rides.createRide(actor, request.copy(type = RideType.INSTANT, scheduledAt = null), "instant")
        assertEquals(RideStatus.SEARCHING, instant.status)
        val nearPickup = Instant.now().plusSeconds(300)
        jdbc.update("UPDATE rides SET scheduled_at=? WHERE id=?", Timestamp.from(nearPickup), scheduled.id)
        scheduler.processDueRides()
        assertEquals("PENDING", status(scheduled.id))

        rides.cancelRide(actor, instant.id, CancelRideRequest(reason = "Test"))
        repeat(2) { scheduler.processDueRides() }
        assertEquals("SEARCHING", status(scheduled.id))
        assertEquals(
            nearPickup.plusSeconds(600).epochSecond,
            jdbc.queryForObject("SELECT search_expires_at FROM rides WHERE id=?", Timestamp::class.java, scheduled.id)!!.toInstant().epochSecond,
        )
        assertEquals(0L, jdbc.queryForObject("SELECT revision FROM ride_dispatch_outbox WHERE ride_id=?", Long::class.java, scheduled.id))

        rides.cancelRide(actor, scheduled.id, CancelRideRequest(reason = "Test"))
        val expired = rides.createRide(actor, request, "later-schedule")
        jdbc.update("UPDATE rides SET scheduled_at=CURRENT_TIMESTAMP - INTERVAL '10 minutes' WHERE id=?", expired.id)
        scheduler.processDueRides()
        assertEquals("EXPIRED", status(expired.id))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM ride_dispatch_outbox WHERE ride_id=?", Long::class.java, expired.id))
    }

    private fun status(rideId: Long) = jdbc.queryForObject("SELECT status FROM rides WHERE id=?", String::class.java, rideId)

    companion object {
        private val schema = "schedule_test_" + UUID.randomUUID().toString().replace("-", "")

        @JvmStatic
        @DynamicPropertySource
        fun dataSource(registry: DynamicPropertyRegistry) {
            registry.add("spring.flyway.schemas") { schema }
            registry.add("spring.datasource.hikari.schema") { schema }
            registry.add("spring.datasource.url") { System.getenv("TEST_DB_URL") }
        }

        @JvmStatic
        @AfterAll
        fun cleanup() {
            DriverManager.getConnection(System.getenv("TEST_DB_URL"), System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD") ?: "")
                .use { connection -> connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
}
