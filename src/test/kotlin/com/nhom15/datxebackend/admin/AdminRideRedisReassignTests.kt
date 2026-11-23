package com.nhom15.datxebackend.admin

import com.nhom15.datxebackend.admin.dto.AdminAssignRideRequest
import com.nhom15.datxebackend.admin.service.AdminRideService
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisTtl
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility
import com.nhom15.datxebackend.matching.service.DriverReleaseService
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.RideDispatchRecoveryTask
import com.nhom15.datxebackend.ride.service.RideQueryService
import com.nhom15.datxebackend.ride.service.RideService
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.geo.Point
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.transaction.PlatformTransactionManager
import java.util.Optional

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminRideRedisReassignTests {

    private var connectionFactory: LettuceConnectionFactory? = null
    private lateinit var redisTemplate: StringRedisTemplate

    @BeforeAll
    fun initRedis() {
        val port = System.getenv("TEST_REDIS_PORT")?.toIntOrNull()
        assumeTrue(
            port != null && port != 6379,
            "Skipped: set TEST_REDIS_PORT to a disposable Redis 7 port (not 6379)",
        )
        val host = System.getenv("TEST_REDIS_HOST")?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
        val factory = LettuceConnectionFactory(RedisStandaloneConfiguration(host, port!!))
        factory.afterPropertiesSet()
        connectionFactory = factory
        redisTemplate = StringRedisTemplate(factory).apply { afterPropertiesSet() }
    }

    @AfterAll
    fun shutdownRedis() {
        connectionFactory?.destroy()
    }

    @BeforeEach
    fun flushDisposableRedis() {
        connectionFactory?.connection?.use { it.serverCommands().flushDb() }
    }

    @Test
    fun `production COMMIT_ADMIN_RIDE_ASSIGNMENT reassigns ride 99 from driver 10 to driver 20 cleanly and is retry-safe`() {
        val commitScript = productionScript("COMMIT_ADMIN_RIDE_ASSIGNMENT")
        val rideId = 99L
        val oldDriverId = 10L
        val newDriverId = 20L
        val unrelatedDriverWithRideId = 99L
        val marker = "admin-assignment:$rideId:$newDriverId"

        // Seed Redis: Ride 99 owned by Driver 10; Driver 20 reserved; Driver 99 (unrelated) also online in GEO
        redisTemplate.opsForValue().set(RedisKeys.driverCurrentRide(oldDriverId.toString()), rideId.toString())
        redisTemplate.opsForValue().set(RedisKeys.driverStatus(oldDriverId.toString()), "BUSY")
        redisTemplate.opsForValue().set(RedisKeys.driverStatus(newDriverId.toString()), "ONLINE")
        redisTemplate.opsForValue().set(RedisKeys.driverLock(newDriverId.toString()), marker)
        redisTemplate.opsForGeo().add(RedisKeys.GEO_KEY, Point(105.8000, 21.0285), oldDriverId.toString())
        redisTemplate.opsForGeo().add(RedisKeys.GEO_KEY, Point(105.8010, 21.0286), newDriverId.toString())
        redisTemplate.opsForGeo().add(RedisKeys.GEO_KEY, Point(105.8020, 21.0287), unrelatedDriverWithRideId.toString())
        redisTemplate.opsForHash<String, String>().putAll(
            RedisKeys.rideState(rideId.toString()),
            mapOf("status" to "DRIVER_ASSIGNED", "driverId" to oldDriverId.toString(), "currentDriver" to oldDriverId.toString()),
        )

        val keys = listOf(
            RedisKeys.rideState(rideId.toString()),
            RedisKeys.rideCandidates(rideId.toString()),
            RedisKeys.rideRejected(rideId.toString()),
            RedisKeys.GEO_KEY,
            RedisKeys.driverLock(newDriverId.toString()),
            RedisKeys.driverCurrentRide(newDriverId.toString()),
            RedisKeys.driverStatus(newDriverId.toString()),
            RedisKeys.driverCurrentRide(oldDriverId.toString()),
            RedisKeys.driverStatus(oldDriverId.toString()),
        )

        val firstResult = redisTemplate.execute(
            commitScript,
            keys,
            marker,
            rideId.toString(),
            newDriverId.toString(),
            oldDriverId.toString(),
            System.currentTimeMillis().toString(),
            RedisTtl.RIDE_STATE.seconds.toString(),
        )

        assertThat(firstResult).isEqualTo(1L)
        // Old driver current_ride cleaned up (no permanent stale lock)
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverCurrentRide(oldDriverId.toString()))).isNull()
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverStatus(oldDriverId.toString()))).isEqualTo("OFFLINE")
        // New driver assigned cleanly & lock deleted
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverCurrentRide(newDriverId.toString()))).isEqualTo("99")
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverStatus(newDriverId.toString()))).isEqualTo("ON_TRIP")
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverLock(newDriverId.toString()))).isNull()
        // GEO/ZSET consistency: driver 10 & 20 removed, unrelated driver 99 NOT removed by rideId
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.GEO_KEY, oldDriverId.toString()) as Any?).isNull()
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.GEO_KEY, newDriverId.toString()) as Any?).isNull()
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.GEO_KEY, unrelatedDriverWithRideId.toString()) as Any?).isNotNull()
        // Ride state & stream consistent
        val rideState = redisTemplate.opsForHash<String, String>().entries(RedisKeys.rideState(rideId.toString()))
        assertThat(rideState["status"]).isEqualTo("DRIVER_ASSIGNED")
        assertThat(rideState["driverId"]).isEqualTo("20")
        assertThat(rideState).doesNotContainKey("currentDriver")
        assertThat(redisTemplate.opsForStream<String, String>().size(RedisKeys.rideEvents(rideId.toString()))).isEqualTo(1L)

        // Retry safety: re-running with the same marker after commit is a no-op (returns 0, preserves state)
        val retryResult = redisTemplate.execute(
            commitScript,
            keys,
            marker,
            rideId.toString(),
            newDriverId.toString(),
            oldDriverId.toString(),
            System.currentTimeMillis().toString(),
            RedisTtl.RIDE_STATE.seconds.toString(),
        )
        assertThat(retryResult).isEqualTo(0L)
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverCurrentRide(newDriverId.toString()))).isEqualTo("99")
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.GEO_KEY, unrelatedDriverWithRideId.toString()) as Any?).isNotNull()
        assertThat(redisTemplate.opsForStream<String, String>().size(RedisKeys.rideEvents(rideId.toString()))).isEqualTo(1L)
    }

    @Test
    fun `failure paths and ownership guards do not corrupt existing assignments`() {
        val commitScript = productionScript("COMMIT_ADMIN_RIDE_ASSIGNMENT")
        val releaseScript = productionScript("RELEASE_ADMIN_DRIVER_RESERVATION")

        // Case 1: Lost/mismatched reservation marker returns 0 and leaves Ride 99 / Driver 10 untouched
        redisTemplate.opsForValue().set(RedisKeys.driverCurrentRide("10"), "99")
        redisTemplate.opsForValue().set(RedisKeys.driverStatus("10"), "BUSY")
        redisTemplate.opsForValue().set(RedisKeys.driverLock("20"), "other-owner")
        redisTemplate.opsForValue().set(RedisKeys.driverStatus("20"), "ONLINE")
        redisTemplate.opsForGeo().add(RedisKeys.GEO_KEY, Point(105.8010, 21.0286), "20")

        val lostMarkerResult = redisTemplate.execute(
            commitScript,
            listOf(
                RedisKeys.rideState("99"),
                RedisKeys.rideCandidates("99"),
                RedisKeys.rideRejected("99"),
                RedisKeys.GEO_KEY,
                RedisKeys.driverLock("20"),
                RedisKeys.driverCurrentRide("20"),
                RedisKeys.driverStatus("20"),
                RedisKeys.driverCurrentRide("10"),
                RedisKeys.driverStatus("10"),
            ),
            "admin-assignment:99:20",
            "99",
            "20",
            "10",
            System.currentTimeMillis().toString(),
            RedisTtl.RIDE_STATE.seconds.toString(),
        )
        assertThat(lostMarkerResult).isEqualTo(0L)
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverCurrentRide("10"))).isEqualTo("99")
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverStatus("10"))).isEqualTo("BUSY")
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverCurrentRide("20"))).isNull()
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.GEO_KEY, "20") as Any?).isNotNull()

        // Case 2: Release reservation on rollback restores Driver 20 to GEO without disturbing Driver 10
        redisTemplate.opsForValue().set(RedisKeys.driverLock("20"), "admin-assignment:99:20")
        redisTemplate.opsForValue().set(RedisKeys.driverAlive("20"), "1")
        redisTemplate.opsForHash<String, String>().putAll(
            RedisKeys.driverLocation("20"),
            mapOf("lat" to "21.0286", "lng" to "105.8010"),
        )
        redisTemplate.opsForZSet().remove(RedisKeys.GEO_KEY, "20")

        val released = redisTemplate.execute(
            releaseScript,
            listOf(
                RedisKeys.driverLock("20"),
                RedisKeys.driverAlive("20"),
                RedisKeys.driverCurrentRide("20"),
                RedisKeys.driverStatus("20"),
                RedisKeys.driverLocation("20"),
                RedisKeys.GEO_KEY,
            ),
            "admin-assignment:99:20",
            "20",
        )
        assertThat(released).isEqualTo(1L)
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverLock("20"))).isNull()
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.GEO_KEY, "20") as Any?).isNotNull()
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverCurrentRide("10"))).isEqualTo("99")

        // Case 3: Old driver already owns a DIFFERENT ride (100) -> COMMIT for ride 99 must NOT delete driver:10:current_ride
        redisTemplate.opsForValue().set(RedisKeys.driverCurrentRide("10"), "100")
        redisTemplate.opsForValue().set(RedisKeys.driverLock("20"), "admin-assignment:99:20")
        val differentRideCommit = redisTemplate.execute(
            commitScript,
            listOf(
                RedisKeys.rideState("99"),
                RedisKeys.rideCandidates("99"),
                RedisKeys.rideRejected("99"),
                RedisKeys.GEO_KEY,
                RedisKeys.driverLock("20"),
                RedisKeys.driverCurrentRide("20"),
                RedisKeys.driverStatus("20"),
                RedisKeys.driverCurrentRide("10"),
                RedisKeys.driverStatus("10"),
            ),
            "admin-assignment:99:20",
            "99",
            "20",
            "10",
            System.currentTimeMillis().toString(),
            RedisTtl.RIDE_STATE.seconds.toString(),
        )
        assertThat(differentRideCommit).isEqualTo(1L)
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverCurrentRide("10"))).isEqualTo("100")
    }

    @Test
    fun `AdminRideService assignDriver reassigns ride 99 from disconnected driver 10 to driver 20 cleanly on Redis 7`() {
        val rideRepo = Mockito.mock(RideRepository::class.java)
        val rideQuery = Mockito.mock(RideQueryService::class.java)
        val rideService = Mockito.mock(RideService::class.java)
        val stateMachine = Mockito.mock(RideStateMachine::class.java)
        val driverRepo = Mockito.mock(DriverRepository::class.java)
        val notificationPort = Mockito.mock(NotificationPort::class.java)
        val eligibility = Mockito.mock(DriverVerificationEligibility::class.java)

        val pickup = Location(GeoPoint(21.0285, 105.8000), "Pickup")
        val dropoff = Location(GeoPoint(21.0350, 105.8100), "Dropoff")
        val ride = Ride(
            customerId = 1L,
            driverId = 10L,
            vehicleId = 5L,
            status = RideStatus.DRIVER_ASSIGNED,
            type = RideType.INSTANT,
            pickup = pickup,
            dropoff = dropoff,
            vehicleType = VehicleType.CAR,
            transmission = Transmission.AUTO,
            fareEstimate = 150_000L,
            paymentMethod = PaymentMethod.CASH,
        ).apply { id = 99L }
        val reassignedRide = Ride(
            customerId = 1L,
            driverId = 20L,
            vehicleId = 5L,
            status = RideStatus.DRIVER_ASSIGNED,
            type = RideType.INSTANT,
            pickup = pickup,
            dropoff = dropoff,
            vehicleType = VehicleType.CAR,
            transmission = Transmission.AUTO,
            fareEstimate = 150_000L,
            paymentMethod = PaymentMethod.CASH,
        ).apply { id = 99L }
        val oldDriver = Driver().apply {
            id = 10L
            firebaseUid = "drv-10"
            status = DriverStatus.BUSY
        }
        val newDriver = Driver().apply {
            id = 20L
            firebaseUid = "drv-20"
            status = DriverStatus.ONLINE
            creditBalance = 200_000L
        }

        Mockito.`when`(rideRepo.findByIdForUpdate(99L)).thenReturn(Optional.of(ride))
        Mockito.`when`(driverRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(oldDriver))
        Mockito.`when`(driverRepo.findByIdForUpdate(20L)).thenReturn(Optional.of(newDriver))
        Mockito.doReturn(true).`when`(eligibility).mayDriveForRide(
            anyNonNull(), anyNonNull(), Mockito.anyBoolean(), Mockito.anyBoolean(), anyNonNull(),
        )
        Mockito.doReturn(false).`when`(rideRepo).existsByDriverIdAndStatusIn(20L, ACTIVE_RIDE_STATUSES)
        Mockito.`when`(stateMachine.adminAssignDriver(99L, 20L, 1L)).thenReturn(reassignedRide to RideStatus.DRIVER_ASSIGNED)

        // Seed Redis: driver 10 disconnected (no alive key) with current_ride=99; driver 20 online & alive; unrelated driver 99 in GEO
        redisTemplate.opsForValue().set(RedisKeys.driverCurrentRide("10"), "99")
        redisTemplate.opsForValue().set(RedisKeys.driverStatus("10"), "BUSY")
        redisTemplate.opsForValue().set(RedisKeys.driverStatus("20"), "ONLINE")
        redisTemplate.opsForValue().set(RedisKeys.driverAlive("20"), "1")
        redisTemplate.opsForHash<String, String>().putAll(
            RedisKeys.driverLocation("20"),
            mapOf("lat" to "21.0286", "lng" to "105.8010"),
        )
        redisTemplate.opsForGeo().add(RedisKeys.GEO_KEY, Point(105.8010, 21.0286), "20")
        redisTemplate.opsForGeo().add(RedisKeys.GEO_KEY, Point(105.8020, 21.0287), "99")

        val service = AdminRideService(
            rideRepository = rideRepo,
            rideQueryService = rideQuery,
            rideService = rideService,
            rideStateMachine = stateMachine,
            driverRepository = driverRepo,
            redisTemplate = redisTemplate,
            notificationPort = notificationPort,
            verification = eligibility,
            matching = MatchingProperties(),
        )

        val admin = AuthUser(1L, "admin-uid", Role.ADMIN, "Admin", null, null, "ACTIVE")
        val response = service.assignDriver(admin, 99L, AdminAssignRideRequest(driverId = 20L))

        assertThat(response.rideId).isEqualTo(99L)
        assertThat(response.currentStatus).isEqualTo(RideStatus.DRIVER_ASSIGNED)
        assertThat(oldDriver.status).isEqualTo(DriverStatus.OFFLINE)
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverCurrentRide("10"))).isNull()
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverStatus("10"))).isEqualTo("OFFLINE")
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverCurrentRide("20"))).isEqualTo("99")
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverStatus("20"))).isEqualTo("ON_TRIP")
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverLock("20"))).isNull()
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.GEO_KEY, "20") as Any?).isNull()
        assertThat(redisTemplate.opsForZSet().score(RedisKeys.GEO_KEY, "99") as Any?).isNotNull()
    }

    @Test
    fun `RideDispatchRecoveryTask cleans up stale current_ride on OFFLINE driver`() {
        val rideRepo = Mockito.mock(RideRepository::class.java)
        val stateMachine = Mockito.mock(RideStateMachine::class.java)
        val outbox = Mockito.mock(RideDispatchOutboxRepository::class.java)
        val offers = Mockito.mock(DispatchOfferRepository::class.java)
        val driverRepo = Mockito.mock(DriverRepository::class.java)
        val txManager = Mockito.mock(PlatformTransactionManager::class.java)
        val eligibility = Mockito.mock(DriverVerificationEligibility::class.java)

        val assignedStatuses = ACTIVE_RIDE_STATUSES.filterNot { it == RideStatus.SEARCHING }
        Mockito.doReturn(emptyList<Ride>()).`when`(rideRepo)
            .findByStatusAndScheduledAtIsNullAndUpdatedAtBefore(anyNonNull(), anyNonNull())
        Mockito.doReturn(emptyList<Ride>()).`when`(rideRepo).findByStatus(RideStatus.SEARCHING)
        Mockito.doReturn(Page.empty<Ride>()).`when`(rideRepo)
            .findAllByStatusIn(anyList(), anyNonNull())
        Mockito.doReturn(emptyList<Driver>()).`when`(driverRepo).findByStatus(DriverStatus.BUSY)
        Mockito.doReturn(null).`when`(rideRepo)
            .findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(10L, assignedStatuses)
        Mockito.doReturn(false).`when`(offers).driverHasOffer(10L)
        Mockito.doReturn(
            Optional.of(Driver().apply { id = 10L; firebaseUid = "drv-10"; status = DriverStatus.OFFLINE }),
        ).`when`(driverRepo).findById(10L)

        // Simulate a stale F-001 lock: driver:10:status=OFFLINE + driver:10:current_ride=99
        redisTemplate.opsForValue().set(RedisKeys.driverStatus("10"), "OFFLINE")
        redisTemplate.opsForValue().set(RedisKeys.driverCurrentRide("10"), "99")

        val driverRelease = DriverReleaseService(redisTemplate, driverRepo)
        val recoveryTask = RideDispatchRecoveryTask(
            rideRepository = rideRepo,
            stateMachine = stateMachine,
            dispatchOutbox = outbox,
            redisTemplate = redisTemplate,
            properties = MatchingProperties(),
            offers = offers,
            drivers = driverRepo,
            driverRelease = driverRelease,
            transactionManager = txManager,
            verification = eligibility,
        )

        recoveryTask.recoverLostDispatches()

        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverCurrentRide("10"))).isNull()
        assertThat(redisTemplate.opsForValue().get(RedisKeys.driverStatus("10"))).isEqualTo("OFFLINE")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T {
        Mockito.any<T>()
        return null as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun productionScript(fieldName: String): DefaultRedisScript<Long> {
        val field = AdminRideService::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(null) as DefaultRedisScript<Long>
    }
}
