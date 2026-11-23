package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.vehicle.dto.CreateVehicleRequest
import com.nhom15.datxebackend.vehicle.dto.UpdateVehicleRequest
import com.nhom15.datxebackend.vehicle.dto.CustomerVehicleSpecifications
import com.nhom15.datxebackend.vehicle.service.VehicleService
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
import org.junit.jupiter.api.assertThrows
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
    "app.bootstrap.admin.email=",
    "app.bootstrap.admin.full-name=",
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
    @Autowired lateinit var garage: VehicleService
    @Autowired lateinit var rideRepository: RideRepository
    @Autowired lateinit var scheduler: ScheduledRideScheduler
    @Autowired lateinit var estimator: com.nhom15.datxebackend.ride.service.RideEstimateService
    @Autowired lateinit var pricing: com.nhom15.datxebackend.pricing.service.PricingRuleService

    @Test fun `estimate validates service area before resolving directions`() {
        val actor = AuthUser(701, "route-estimate", Role.CUSTOMER, null, null, null, "ACTIVE")
        val outside = com.nhom15.datxebackend.ride.dto.request.EstimateRideRequest(
            vehicleId = 1L, pickupAddress = "Điểm đón", pickupLat = 20.0, pickupLng = 105.84,
            dropoffAddress = "Điểm đến", dropoffLat = 21.04, dropoffLng = 105.85,
        )

        val error = assertThrows<AppException> { estimator.estimateRide(actor, outside) }
        assertEquals(ErrorCode.OUT_OF_SERVICE_AREA, error.errorCode)
        org.mockito.Mockito.verifyNoInteractions(routes)
    }

    @Test fun `scheduled checkout keeps a voucher that expires before pickup unused`() {
        val customerId = jdbc.queryForObject("INSERT INTO customers(firebase_uid) VALUES ('test-voucher-schedule') RETURNING id", Long::class.java)!!
        val actor = AuthUser(customerId, "test-voucher-schedule", Role.CUSTOMER, null, null, null, "ACTIVE")
        val vehicleId = garage.create(actor, CreateVehicleRequest("SCH-VCHR", Transmission.AUTO, VehicleType.CAR)).id
        val pickup = GeoPoint(21.03, 105.84)
        val dropoff = GeoPoint(21.04, 105.85)
        val request = CreateRideRequest(
            vehicleId = vehicleId, type = RideType.SCHEDULED, scheduledAt = Instant.now().plusSeconds(7_200),
            pickupAddress = "Đón", pickupLat = pickup.lat, pickupLng = pickup.lng,
            dropoffAddress = "Đến", dropoffLat = dropoff.lat, dropoffLng = dropoff.lng,
            voucherCode = "SCHEDULEVOUCHER",
        )
        jdbc.update("UPDATE promo_budget SET budget_limit=100000 WHERE id=1")
        jdbc.update("""INSERT INTO vouchers(code,discount_type,value,minimum_order_amount,valid_from,valid_to,active,usage_limit,usage_count)
            VALUES('SCHEDULEVOUCHER','FIXED',10000,0,CURRENT_TIMESTAMP - interval '1 hour', CURRENT_TIMESTAMP + interval '1 hour', TRUE, 100, 0)""")
        `when`(routes.resolve(customerId, vehicleId, VehicleType.CAR, pickup, dropoff, null)).thenReturn(
            SavedRideRoute("schedule-voucher-route", PolylineCodec.encode(listOf(pickup, dropoff)), 2_000.0, 300.0, "TEST", VehicleType.CAR),
        )

        val error = assertThrows<AppException> { rides.createRide(actor, request, "expires-before-pickup") }
        assertEquals(ErrorCode.VOUCHER_INVALID, error.errorCode)
        assertEquals(0, jdbc.queryForObject("SELECT usage_count FROM vouchers WHERE code='SCHEDULEVOUCHER'", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rides WHERE customer_id=?", Int::class.java, customerId))
        assertEquals(0L, jdbc.queryForObject("SELECT held_amount FROM promo_budget WHERE id=1", Long::class.java))

        jdbc.update("UPDATE vouchers SET valid_to=CURRENT_TIMESTAMP + INTERVAL '3 hours' WHERE code='SCHEDULEVOUCHER'")
        rides.createRide(actor, request, "valid-at-pickup")
        assertEquals(10_000L, jdbc.queryForObject("SELECT held_amount FROM promo_budget WHERE id=1", Long::class.java))
    }

    @Test fun `tariff edits preserve the fare breakdown for an already booked ride`() {
        val customerId = jdbc.queryForObject("INSERT INTO customers(firebase_uid) VALUES ('test-pricing-schedule') RETURNING id", Long::class.java)!!
        val actor = AuthUser(customerId, "test-pricing-schedule", Role.CUSTOMER, null, null, null, "ACTIVE")
        val admin = AuthUser(707, "test-pricing-admin", Role.ADMIN, null, null, null, "ACTIVE")
        val vehicleId = garage.create(actor, CreateVehicleRequest("SCH-PRICE", Transmission.AUTO, VehicleType.CAR)).id
        val pickup = GeoPoint(21.03, 105.84)
        val dropoff = GeoPoint(21.04, 105.85)
        val request = CreateRideRequest(
            vehicleId = vehicleId, type = RideType.SCHEDULED, scheduledAt = Instant.now().plusSeconds(7_200),
            pickupAddress = "Đón", pickupLat = pickup.lat, pickupLng = pickup.lng,
            dropoffAddress = "Đến", dropoffLat = dropoff.lat, dropoffLng = dropoff.lng,
        )
        `when`(routes.resolve(customerId, vehicleId, VehicleType.CAR, pickup, dropoff, null)).thenReturn(
            SavedRideRoute("schedule-pricing-route", PolylineCodec.encode(listOf(pickup, dropoff)), 2_000.0, 300.0, "TEST", VehicleType.CAR),
        )
        val rule = jdbc.queryForMap("SELECT * FROM pricing_rules WHERE active AND vehicle_type='CAR'")
        val ruleId = (rule.getValue("id") as Number).toLong()
        val baseFare = (rule.getValue("base_fare") as Number).toLong()
        val ride = rides.createRide(actor, request, "preserve-booking-fare")

        pricing.updateRule(admin, ruleId, com.nhom15.datxebackend.pricing.dto.UpdatePricingRuleRequest(
            baseFare = baseFare + 10_000, perKm = 1_000, perMinuteWaiting = 0,
            nightSurchargePercent = 0, returnFeeForDriver = 0, minFare = 0,
        ))
        try {
            val booked = jdbc.queryForMap("SELECT * FROM rides WHERE id=?", ride.id)
            assertEquals(ruleId, booked["pricing_rule_id"])
            assertEquals(baseFare, booked["pricing_base_fare"])
            assertEquals((rule.getValue("per_km") as Number).toLong() * 2, booked["pricing_distance_fare"])
            assertEquals(ride.estimatedFare, booked["fare_estimate"])
            assertEquals(baseFare, jdbc.queryForObject(
                "SELECT (previous_rule->>'base_fare')::bigint FROM pricing_rule_changes WHERE pricing_rule_id=? AND admin_id=?",
                Long::class.java, ruleId, admin.id,
            ))
        } finally {
            jdbc.update("""UPDATE pricing_rules SET base_fare=?,per_km=?,per_minute_waiting=?,night_surcharge_percent=?,
                night_start=?,night_end=?,return_fee_for_driver=?,min_fare=? WHERE id=?""",
                rule["base_fare"], rule["per_km"], rule["per_minute_waiting"], rule["night_surcharge_percent"],
                rule["night_start"], rule["night_end"], rule["return_fee_for_driver"], rule["min_fare"], ruleId,
            )
        }
    }

    @Test fun `concurrent booking leaves one schedule and a customer can ride immediately while it waits`() {
        val customerId = jdbc.queryForObject("INSERT INTO customers(firebase_uid) VALUES ('test-customer') RETURNING id", Long::class.java)!!
        val actor = AuthUser(customerId, "test-customer", Role.CUSTOMER, null, null, null, "ACTIVE")
        val specs = CustomerVehicleSpecifications(passengerSeats = 4, trailerMassKg = 0)
        val vehicleId = garage.create(actor, CreateVehicleRequest("TEST-01", Transmission.AUTO, VehicleType.CAR,
            brand = "Synthetic", model = "Original", specifications = specs)).id
        val spare = garage.create(actor, CreateVehicleRequest("TEST-02", Transmission.MANUAL, VehicleType.CAR)).id
        garage.setDefault(actor, spare)
        garage.setDefault(actor, vehicleId)
        assertEquals(listOf(vehicleId), garage.list(actor).filter { it.isDefault }.map { it.id })
        val otherId = jdbc.queryForObject("INSERT INTO customers(firebase_uid) VALUES ('test-other') RETURNING id", Long::class.java)!!
        val other = actor.copy(id = otherId, firebaseUid = "test-other")
        assertInstanceOf(NotFoundException::class.java, runCatching { garage.setDefault(other, vehicleId) }.exceptionOrNull())
        assertInstanceOf(NotFoundException::class.java, runCatching { garage.delete(other, vehicleId) }.exceptionOrNull())
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
        garage.update(actor, vehicleId, UpdateVehicleRequest("TEST-11", Transmission.MANUAL, VehicleType.CAR,
            brand = "Synthetic", model = "Renamed"))
        assertEquals(4, garage.list(actor).first { it.id == vehicleId }.specifications?.passengerSeats)
        val historic = rideRepository.findById(expired.id).orElseThrow()
        assertEquals("Original", historic.vehicleBookingSnapshot?.model)
        assertEquals("car-auto", historic.vehicleBookingSnapshot?.capabilityGroupId)
        assertEquals("CUSTOMER_DECLARED", historic.vehicleBookingSnapshot?.source)
        assertEquals("TEST-01", historic.licensePlate)
        assertEquals(Transmission.AUTO, historic.transmission)
        check(runCatching {
            jdbc.update("UPDATE rides SET vehicle_booking_snapshot='{}'::jsonb WHERE id=?", expired.id)
        }.isFailure)
        garage.delete(actor, vehicleId)
        assertEquals(listOf(spare), garage.list(actor).map { it.id })
        assertEquals(expired.id, rides.createRide(actor, request, "later-schedule").id)
        check(runCatching { rides.createRide(actor, request, "archive-blocked") }.isFailure)
        assertEquals(garage.create(actor, CreateVehicleRequest("TEST-11", Transmission.AUTO, VehicleType.CAR)).plateNumber,
            "TEST-11")

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
