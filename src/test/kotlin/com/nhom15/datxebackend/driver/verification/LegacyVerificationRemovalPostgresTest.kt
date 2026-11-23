package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.admin.repository.AdminDriverQueryRepository
import com.nhom15.datxebackend.chat.service.ConversationService
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.event.EventPublisher
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.config.properties.ScheduledRideProperties
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.service.WalletService
import com.nhom15.datxebackend.ride.entity.HandoverPhase
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.entity.VehicleHandoverReport
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.repository.RideStatusHistoryRepository
import com.nhom15.datxebackend.ride.repository.VehicleHandoverReportRepository
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.ride.service.state.RideTransitionCommand
import com.nhom15.datxebackend.ride.service.state.RideTransitionEvent
import com.nhom15.datxebackend.ride.service.state.driverRideActor
import com.nhom15.datxebackend.voucher.service.VoucherService
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.util.Optional
import java.util.Properties
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when` as stub
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.json.JsonMapper

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class LegacyVerificationRemovalPostgresTest {
    @TempDir
    lateinit var storage: Path

    @Test
    fun migratesMixedDataAndCountsApplications() {
        val schema = "legacy_removal_" + UUID.randomUUID().toString().replace("-", "")
        val ds = DriverManagerDataSource(System.getenv("TEST_DB_URL"), System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"))
        val admin = JdbcTemplate(ds)
        try {
            Flyway.configure().dataSource(ds).schemas(schema).target("41").load().migrate()
            ds.connectionProperties = Properties().apply { setProperty("currentSchema", schema) }
            val jdbc = JdbcTemplate(ds)
            jdbc.update("""
                INSERT INTO drivers(id, firebase_uid, phone_number, verification_status, license_verified, status, online_vehicle_type,
                                    avatar_public_id, license_image_url, income_balance)
                VALUES (101, 'manual', '0900000101', 'APPROVED', true, 'BUSY', 'CAR', 'avatar-ref', 'legacy-license', 123000),
                       (102, 'native', '0900000102', 'APPROVED', true, 'ONLINE', 'CAR', NULL, NULL, 0),
                       (103, 'mixed',  '0900000103', 'APPROVED', true, 'ONLINE', 'CAR', NULL, NULL, 0),
                       (104, 'empty',  '0900000104', 'PENDING', false, 'OFFLINE', NULL, NULL, NULL, 0),
                       (105, 'review', '0900000105', 'PENDING', false, 'OFFLINE', NULL, NULL, NULL, 0),
                       (106, 'assigned', '0900000106', 'APPROVED', true, 'BUSY', 'CAR', NULL, NULL, 0),
                       (107, 'native-only', '0900000107', 'APPROVED', true, 'ONLINE', 'CAR', NULL, NULL, 0)
            """)
            jdbc.update("""
                INSERT INTO driver_documents(driver_id, document_type, secure_url, cloudinary_public_id, verification_status)
                VALUES (101, 'DRIVER_LICENSE', 'https://example.test/legacy', 'legacy-only', 'APPROVED'),
                       (102, 'CCCD', 'https://example.test/native', 'legacy-but-native-approved', 'APPROVED'),
                       (103, 'CCCD', 'https://example.test/mixed', 'legacy-mixed', 'APPROVED')
            """)
            jdbc.update("""
                INSERT INTO driver_vehicle_skills(driver_id, vehicle_type, transmission, license_class, verified)
                VALUES (101, 'CAR', 'AUTO', 'B2', true)
            """)
            jdbc.update("""
                INSERT INTO notifications(recipient_driver_id, notification_type, title, body)
                VALUES (101, 'DRIVER_DOCUMENT_REVIEWED', 'legacy', 'legacy'),
                       (101, 'DRIVER_VERIFICATION_UPDATED', 'ekyc', 'ekyc'),
                       (101, 'RIDE_UPDATED', 'ride', 'ride')
            """)
            jdbc.update("INSERT INTO customers(id, firebase_uid, phone_number, status) VALUES (21, 'customer', '0900000021', 'ACTIVE'), (22, 'assigned-customer', '0900000022', 'ACTIVE')")
            jdbc.update("INSERT INTO vehicles(id, customer_id, plate_number, vehicle_type, transmission_type) VALUES (31, 21, '29A-90000', 'CAR', 'AUTO'), (32, 22, '29A-90001', 'CAR', 'AUTO')")
            jdbc.update("""
                INSERT INTO rides(id, customer_id, driver_id, vehicle_id, status, type, vehicle_type, transmission,
                    fare_estimate, discount_amount, payment_method, pickup_lat, pickup_lng, pickup_address, dropoff_lat, dropoff_lng, dropoff_address)
                VALUES (41, 21, 101, 31, 'IN_PROGRESS', 'INSTANT', 'CAR', 'AUTO', 100000, 0, 'CASH', 21, 105, 'A', 21.1, 105.1, 'B'),
                       (42, 22, 106, 32, 'DRIVER_ASSIGNED', 'INSTANT', 'CAR', 'AUTO', 100000, 0, 'CASH', 21, 105, 'A', 21.1, 105.1, 'B'),
                       (43, 21, 101, 31, 'COMPLETED', 'INSTANT', 'CAR', 'AUTO', 100000, 0, 'CASH', 21, 105, 'A', 21.1, 105.1, 'B')
            """)

            jdbc.update("INSERT INTO wallet_transactions(id, driver_id, ride_id, amount, type, transaction_type, wallet_type) VALUES (61, 101, 43, 23000, 'TRIP_EARNING', 'RIDE_PAYMENT', 'CASH')")
            jdbc.update("INSERT INTO ratings(id, ride_id, direction, ratee_id, rater_id, stars) VALUES (71, 43, 'CUSTOMER_TO_DRIVER', 101, 21, 5)")

            val manual = application(jdbc, "manual", "APPROVED", 2, 1, 2, 1)
            val native = application(jdbc, "native", "APPROVED", 3, 1, 3, 1)
            val nativeOnly = application(jdbc, "native-only", "APPROVED", 3, 1, 3, 1)
            val mixed = application(jdbc, "mixed", "SUBMITTED", 6, 6, 2, 1)
            val review = application(jdbc, "review", "UNDER_REVIEW", 4, 3, null, null)
            snapshot(jdbc, manual, 1, """{"legacyBackfill":true,"confirmed":{"cccd_id":"000000000001"}}""")
            snapshot(jdbc, native, 1, """{"warningCount":0}""")
            snapshot(jdbc, nativeOnly, 1, """{"warningCount":0}""")
            snapshot(jdbc, mixed, 1, """{"legacyBackfill":true}""")
            snapshot(jdbc, mixed, 6, """{"warningCount":2}""")
            snapshot(jdbc, review, 1, """{"warningCount":0}""")
            snapshot(jdbc, review, 3, """{"warningCount":1}""")

            for ((uid, app, revision) in listOf(Triple("manual", manual, 2), Triple("native", native, 3), Triple("native-only", nativeOnly, 3), Triple("mixed", mixed, 2))) {
                jdbc.update("""
                    INSERT INTO driver_verification_projection(owner_uid, application_id, revision, status, entitlements, approved_capabilities)
                    VALUES (?, ?, ?, 'APPROVED', '[]', '[]')
                """, uid, app, revision)
            }
            val mapper = JsonMapper.builder().findAndAddModules().build()
            val entitlement = LicenseEntitlement("B", LicenseValidity.HAS_EXPIRY, LocalDate.of(2035, 1, 1))
            for ((driverId, app) in listOf(102L to native, 107L to nativeOnly)) {
                val capability = ApprovedCapability("car-auto", app, 1, 3, LicenseEligibilityPolicy.VERSION, listOf(entitlement))
                jdbc.update("UPDATE driver_verification_projection SET approved_capabilities = ?, entitlements = ? WHERE application_id = ?",
                    mapper.writeValueAsString(listOf(capability)), mapper.writeValueAsString(listOf(entitlement)), app)
                jdbc.update("INSERT INTO driver_capability_preferences(driver_id, approval_revision, version, enabled_groups) VALUES (?, 3, 1, ?)",
                    driverId, mapper.writeValueAsString(listOf("car-auto")))
                jdbc.update("INSERT INTO driver_verification_audit(application_id, revision, actor_uid, actor_role, action) VALUES (?, 3, 'reviewer', 'admin', 'APPROVED')", app)
            }
            jdbc.update("INSERT INTO driver_verification_audit(application_id, revision, actor_uid, actor_role, action) VALUES (?, 6, 'mixed', 'driver', 'RESUBMIT')", mixed)
            jdbc.update("""
                INSERT INTO driver_verification_captures(id, application_id, slot, storage_key, mime_type, byte_size, sha256, quality_json, captured_revision)
                VALUES (?, ?, 'cccd_front', 'encrypted-test', 'image/jpeg', 43, repeat('0',64), '{}', 5)
            """, UUID.randomUUID(), mixed)

            val flyway = Flyway.configure().dataSource(ds).schemas(schema).load()
            // V42 was the tip when this fixture was written; V43..V46 now follow it.
            assertEquals(5, flyway.migrate().migrationsExecuted)
            flyway.validate()
            assertEquals("46", flyway.info().current().version.version)
            assertNull(jdbc.queryForObject("SELECT to_regclass('driver_documents')::text", String::class.java))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'drivers' AND column_name = 'license_image_url'", Int::class.java, schema))
            assertEquals("BUSY/CAR/PENDING", jdbc.queryForObject(
                "SELECT status || '/' || online_vehicle_type || '/' || verification_status FROM drivers WHERE id = 101", String::class.java))
            assertEquals("OFFLINE", jdbc.queryForObject("SELECT status FROM drivers WHERE id = 103", String::class.java))
            assertEquals("IN_PROGRESS", jdbc.queryForObject("SELECT status FROM rides WHERE id = 41", String::class.java))
            assertEquals(123000L, jdbc.queryForObject("SELECT income_balance FROM drivers WHERE id = 101", Long::class.java))
            assertEquals("avatar-ref", jdbc.queryForObject("SELECT avatar_public_id FROM drivers WHERE id = 101", String::class.java))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM driver_vehicle_skills WHERE driver_id = 101", Int::class.java))
            assertEquals(listOf("DRIVER_VERIFICATION_UPDATED", "RIDE_UPDATED"), jdbc.queryForList("SELECT notification_type FROM notifications ORDER BY id", String::class.java))
            assertEquals("DRAFT", field(jdbc, "status", manual))
            assertNull(field(jdbc, "locked_cccd_id", manual))
            assertEquals("SUBMITTED", field(jdbc, "status", mixed))
            assertEquals("6", field(jdbc, "submission_revision::text", mixed))
            assertNull(field(jdbc, "approved_revision::text", mixed))
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM driver_verification_captures WHERE application_id = ?", Int::class.java, mixed))

            val projections = VerificationProjectionRepository(jdbc, mapper, requireSyncCheckpoint = false)
            assertNull(projections.current("manual"))
            assertNull(projections.current("mixed"))
            assertNull(projections.current("empty"))
            assertEquals(VerificationStatus.APPROVED, assertNotNull(projections.current("native")).status)
            val eligibility = DriverVerificationEligibility(projections)
            assertTrue(eligibility.mayGoOnline(Driver().apply { firebaseUid = "native" }, VehicleType.CAR))
            assertTrue(eligibility.mayGoOnline(Driver().apply { firebaseUid = "native-only" }, VehicleType.CAR))
            assertFalse(eligibility.mayGoOnline(Driver().apply { firebaseUid = "manual"; verificationStatus = "APPROVED" }, VehicleType.CAR))
            exerciseMigratedRides(jdbc, eligibility)
            assertEquals(23000L, jdbc.queryForObject("SELECT amount FROM wallet_transactions WHERE id = 61 AND ride_id = 43", Long::class.java))
            assertEquals(5, jdbc.queryForObject("SELECT stars FROM ratings WHERE id = 71 AND ride_id = 43", Int::class.java))
            assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM rides WHERE id = 43 AND driver_id = 101", String::class.java))
            assertEquals(123000L, jdbc.queryForObject("SELECT income_balance FROM drivers WHERE id = 101", Long::class.java))
            val admins = AdminDriverQueryRepository(jdbc)
            assertEquals("PENDING", admins.findById(103)?.verificationStatus)
            assertEquals("APPROVED", admins.findById(102)?.verificationStatus)
            assertEquals(mixed, admins.findVerification(103)?.applicationId)
            assertNull(admins.findVerification(104))

            val workflow = DriverVerificationWorkflowService(jdbc, mapper, DriverVerificationStorageService(storage.toString(), ""), projections, mock(DriverRepository::class.java))
            val actor = AuthUser(1, "reviewer", Role.ADMIN, null, null, null, "ACTIVE")
            val queue = workflow.reviewQueue(actor, "PENDING_REVIEW", null, limit = 1, offset = 0)
            assertEquals(2L, queue["totalElements"])
            assertEquals(true, queue["hasMore"])
            val filtered = workflow.reviewQueue(actor, "PENDING_REVIEW", mixed, limit = 10, offset = 0)
            assertEquals(1L, filtered["totalElements"])
            assertEquals(mixed.toString(), ((filtered["items"] as List<*>).single() as Map<*, *>)["id"])
            assertEquals(2L, workflow.reviewQueue(actor, "PENDING_REVIEW", null, 1, 50)["totalElements"])
        } finally {
            admin.execute("""DROP SCHEMA IF EXISTS "$schema" CASCADE""")
        }
    }


    private fun exerciseMigratedRides(jdbc: JdbcTemplate, eligibility: DriverVerificationEligibility) {
        val driverIds = mapOf(41L to 101L, 42L to 106L)
        val point = Location(GeoPoint(21.0, 105.0), "Synthetic")
        fun readRide(id: Long) = Ride(
            customerId = if (id == 41L) 21 else 22, driverId = driverIds.getValue(id),
            vehicleId = if (id == 41L) 31 else 32,
            type = RideType.INSTANT, pickup = point, dropoff = point, vehicleType = VehicleType.CAR,
            transmission = Transmission.AUTO, fareEstimate = 100000, paymentMethod = PaymentMethod.CASH,
            status = RideStatus.valueOf(jdbc.queryForObject("SELECT status FROM rides WHERE id = ?", String::class.java, id)!!),
        ).apply { this.id = id }

        fun readDriver(id: Long) = jdbc.queryForObject(
            "SELECT firebase_uid, status, online_vehicle_type, verification_status FROM drivers WHERE id = ?",
            { row, _ -> Driver().apply {
                this.id = id
                firebaseUid = row.getString("firebase_uid")
                status = DriverStatus.valueOf(row.getString("status"))
                onlineVehicleType = row.getString("online_vehicle_type")?.let(VehicleType::valueOf)
                verificationStatus = row.getString("verification_status")
            } },
            id,
        )

        val rides = mock(RideRepository::class.java)
        val drivers = mock(DriverRepository::class.java)
        val handovers = mock(VehicleHandoverReportRepository::class.java)
        val events = mock(EventPublisher::class.java)
        for ((rideId, driverId) in driverIds) {
            stub(rides.findByIdForUpdate(rideId)).thenAnswer { Optional.of(readRide(rideId)) }
            stub(drivers.findByIdForUpdate(driverId)).thenAnswer { Optional.of(readDriver(driverId)) }
        }
        val returns = VehicleHandoverReport(readRide(41), HandoverPhase.DROPOFF, 1234, confirmedByCustomer = true)
        stub(handovers.findByRideIdAndPhase(41, HandoverPhase.DROPOFF)).thenReturn(returns)
        for ((from, to) in listOf("IN_PROGRESS" to "ARRIVED_DESTINATION", "ARRIVED_DESTINATION" to "VEHICLE_RETURNED", "VEHICLE_RETURNED" to "COMPLETED")) {
            stub(rides.compareAndSetStatus(41, from, to, 0)).thenAnswer {
                jdbc.update("UPDATE rides SET status = ? WHERE id = 41 AND status = ?", to, from)
            }
        }

        doAnswer {
            val driver = it.getArgument<Driver>(0)
            jdbc.update("UPDATE drivers SET status = ?, online_vehicle_type = ? WHERE id = ?",
                driver.status.name, driver.onlineVehicleType?.name, driver.id)
            driver
        }.`when`(drivers).save(any(Driver::class.java) ?: Driver())
        val machine = RideStateMachine(
            rides, mock(RideStatusHistoryRepository::class.java), events, handovers,
            mock(ConversationService::class.java), MatchingProperties(),
            mock(RideDispatchOutboxRepository::class.java), mock(DispatchOfferRepository::class.java),
            drivers, mock(WalletService::class.java), mock(VoucherService::class.java), ScheduledRideProperties(),
            Clock.systemUTC(), eligibility,
        )

        val assigned = readRide(42)
        assertEquals(RideStatus.DRIVER_ASSIGNED, assigned.status)
        assertEquals(DriverStatus.BUSY, readDriver(106).status)
        val blocked = assertFailsWith<ConflictException> {
            machine.transition(RideTransitionCommand(42, RideTransitionEvent.START_TRIP, driverRideActor(106)))
        }
        assertEquals(ErrorCode.DRIVER_NOT_VERIFIED, blocked.errorCode)
        assertEquals(RideStatus.DRIVER_ASSIGNED, readRide(42).status)

        for (event in listOf(RideTransitionEvent.ARRIVE_DESTINATION, RideTransitionEvent.DROPOFF_HANDOVER_CREATED, RideTransitionEvent.COMPLETE)) {
            val result = machine.transition(RideTransitionCommand(41, event, driverRideActor(101)))
            if (event == RideTransitionEvent.COMPLETE) assertNotNull(result.completedAt)
        }
        assertEquals(RideStatus.COMPLETED, readRide(41).status)
        val driver = readDriver(101)
        assertEquals(DriverStatus.OFFLINE, driver.status)
        assertNull(driver.onlineVehicleType)
        assertFalse(eligibility.mayGoOnline(driver, VehicleType.CAR))
        driver.status = DriverStatus.ONLINE
        driver.onlineVehicleType = VehicleType.CAR
        assertFalse(eligibility.mayDriveForRide(driver, assigned))
    }

    private fun application(jdbc: JdbcTemplate, uid: String, status: String, revision: Long, submitted: Long,
                            approved: Long?, approvedSubmission: Long?): UUID {
        val id = UUID.randomUUID()
        jdbc.update("""
            INSERT INTO driver_verification_applications(id, owner_uid, status, revision, submission_revision,
                submitted_at, approved_revision, approved_submission_revision, locked_cccd_id, confirmation_current)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, '000000000001', true)
        """, id, uid, status, revision, submitted, approved, approvedSubmission)
        return id
    }

    private fun snapshot(jdbc: JdbcTemplate, id: UUID, revision: Long, snapshot: String) {
        jdbc.update("INSERT INTO driver_verification_submissions(application_id, revision, submitted_at, snapshot_json) VALUES (?, ?, CURRENT_TIMESTAMP, ?)", id, revision, snapshot)
    }

    private fun field(jdbc: JdbcTemplate, column: String, id: UUID): String? =
        jdbc.queryForObject("SELECT $column FROM driver_verification_applications WHERE id = ?", String::class.java, id)
}
