package com.nhom15.datxebackend.integration

import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.notification.api.DeliveryResult
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.NotificationPayload
import com.nhom15.datxebackend.rating.dto.CreateRideRatingRequest
import com.nhom15.datxebackend.rating.service.RideRatingService
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.Optional
import java.util.Properties
import java.util.UUID

/** Kiểm thử nâng cấp Flyway V1..V33 -> V34..V46 trên dữ liệu tổng hợp và tương tác các tính năng tích hợp. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class IntegrationReleaseRegressionPostgresTests {
    @Test
    fun `upgrades from origin V33 schema to V42 preserving existing rows and supporting integrated features`() {
        val schema = "rel_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        val ds = DriverManagerDataSource(
            System.getenv("TEST_DB_URL"),
            System.getenv("TEST_DB_USER"),
            System.getenv("TEST_DB_PASSWORD"),
        )
        val admin = JdbcTemplate(ds)
        try {
            // 1. Khởi tạo schema tại mốc origin/main (V1..V33)
            Flyway.configure()
                .dataSource(ds)
                .schemas(schema)
                .target("33")
                .load()
                .migrate()

            ds.connectionProperties = Properties().apply { setProperty("currentSchema", schema) }
            val jdbc = JdbcTemplate(ds)

            // 2. Tạo dữ liệu tổng hợp giống database hiện có trước khi nâng cấp V34..V39
            jdbc.update(
                """INSERT INTO customers(id, firebase_uid, full_name, phone_number, status)
                   VALUES (101, 'upg-c1', 'Khách Tổng Hợp', '0901000101', 'ACTIVE')""",
            )
            jdbc.update(
                """INSERT INTO drivers(id, firebase_uid, full_name, phone_number, avatar_url, verification_status, license_class, status)
                   VALUES (201, 'upg-d1', 'Tài Xế Đã Duyệt', '0912000201', 'https://cdn.example.test/portrait.jpg', 'APPROVED', 'B2', 'OFFLINE')""",
            )
            jdbc.update(
                """INSERT INTO vehicles(id, customer_id, plate_number, vehicle_type, transmission_type)
                   VALUES (301, 101, '29A-99901', 'CAR', 'AUTO')""",
            )
            jdbc.update(
                """INSERT INTO rides(id, customer_id, driver_id, vehicle_id, status, type, vehicle_type, transmission,
                                     fare_estimate, discount_amount, final_fare, payment_method,
                                     pickup_lat, pickup_lng, pickup_address, dropoff_lat, dropoff_lng, dropoff_address,
                                     completed_at)
                   VALUES (401, 101, 201, 301, 'COMPLETED', 'INSTANT', 'CAR', 'AUTO',
                           50000, 50000, 0, 'CASH', 21.0, 105.8, 'Điểm đón', 21.05, 105.85, 'Điểm trả',
                           CURRENT_TIMESTAMP)""",
            )
            jdbc.update(
                """INSERT INTO vouchers(id, code, campaign_title, discount_type, value, minimum_order_amount,
                                        valid_from, valid_to, active, source, campaign_budget_limit)
                   VALUES (501, 'TET50', 'Chiến dịch Tết', 'FIXED', 50000, 0,
                           CURRENT_TIMESTAMP - interval '1 day', CURRENT_TIMESTAMP + interval '7 days',
                           TRUE, 'PUBLIC_CODE', 1000000)""",
            )
            jdbc.update(
                """INSERT INTO voucher_campaign_budget(campaign_id, budget_limit, held_amount, spent_amount, released_amount)
                   VALUES (501, 1000000, 50000, 0, 0)""",
            )
            jdbc.update(
                """INSERT INTO ratings(id, ride_id, direction, ratee_id, rater_id, stars, comment, is_hidden)
                   VALUES (601, 401, 'CUSTOMER_TO_DRIVER', 201, 101, 2, 'Bình luận cần ẩn', TRUE)""",
            )

            // 3. Chạy nâng cấp Flyway lên phiên bản mới nhất V34..V46
            val result = Flyway.configure()
                .dataSource(ds)
                .schemas(schema)
                .load()
                .migrate()

            assertTrue(result.migrationsExecuted >= 9, "Phải chạy tối thiểu 9 migration mới V34..V42")
            val appliedVersions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String::class.java,
            )
            assertTrue(appliedVersions.contains("39"))
            assertTrue(appliedVersions.contains("40"))
            assertTrue(appliedVersions.contains("43"))
            assertTrue(appliedVersions.contains("44"))
            assertTrue(appliedVersions.contains("45"))
            assertEquals("46", appliedVersions.last())

            // 4. Dữ liệu avatar_url, rating moderation và voucher campaign được bảo toàn nguyên vẹn
            assertEquals(
                "https://cdn.example.test/portrait.jpg",
                jdbc.queryForObject("SELECT avatar_url FROM drivers WHERE id = 201", String::class.java),
            )
            assertEquals(
                "Chiến dịch Tết",
                jdbc.queryForObject("SELECT campaign_title FROM vouchers WHERE id = 501", String::class.java),
            )
            assertEquals(
                true,
                jdbc.queryForObject("SELECT is_hidden FROM ratings WHERE id = 601", Boolean::class.java),
            )

            // 5. Legacy APPROVED driver cần xác minh lại sau khi hoàn tất chuỗi migration
            val appStatus = jdbc.queryForObject(
                "SELECT status FROM driver_verification_applications WHERE owner_uid = 'upg-d1'",
                String::class.java,
            )
            assertEquals("DRAFT", appStatus)
            val projCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM driver_verification_projection WHERE owner_uid = 'upg-d1' AND (native_workflow OR synced_workflow)",
                Int::class.java,
            )
            assertEquals(0, projCount)

            // 6. Kiểm tra đồng thời hai API rating: ẩn danh + hasRatedDriver (cả khi đánh giá đã bị ẩn)
            val notified = mutableListOf<NotificationPayload>()
            val rideRepo = mockRideRepository(401, 101, 201)
            val ratingService = RideRatingService(
                rideRepo,
                jdbc,
                object : NotificationPort {
                    override fun send(to: Recipient, payload: NotificationPayload): DeliveryResult {
                        notified += payload
                        return DeliveryResult.SENT_PUSH
                    }
                },
            )
            assertTrue(ratingService.hasRatedDriver(401, 101))
            assertFalse(ratingService.hasRatedCustomer(401, 201))

            val driverActor = AuthUser(201, "upg-d1", Role.DRIVER, "Tài Xế Đã Duyệt", null, null, "ACTIVE")
            val received = ratingService.received(driverActor, 0, 20)
            // totalRatings must match averageRating and therefore counts hidden ratings;
            // only the paged content list filters them out.
            assertEquals(1L, received.totalRatings)
            assertTrue(received.ratings.isEmpty())

            // Tài xế đánh giá lại khách -> phát thông báo ẩn danh + hasRatedCustomer = true
            ratingService.rate(driverActor, 401, CreateRideRatingRequest(stars = 5, comment = "Khách lịch sự"))
            assertTrue(ratingService.hasRatedCustomer(401, 201))
            assertEquals(1, notified.size)
            assertTrue(notified.single().body.contains("ẩn danh"))
        } finally {
            admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun mockRideRepository(rideId: Long, customerId: Long, driverId: Long): RideRepository {
        val repo = Mockito.mock(RideRepository::class.java)
        val ride = com.nhom15.datxebackend.ride.entity.Ride(
            customerId = customerId,
            driverId = driverId,
            vehicleId = 301,
            type = com.nhom15.datxebackend.ride.entity.RideType.INSTANT,
            pickup = com.nhom15.datxebackend.common.domain.Location(
                com.nhom15.datxebackend.common.domain.GeoPoint(21.0, 105.8),
                "Điểm đón",
            ),
            dropoff = com.nhom15.datxebackend.common.domain.Location(
                com.nhom15.datxebackend.common.domain.GeoPoint(21.05, 105.85),
                "Điểm trả",
            ),
            vehicleType = com.nhom15.datxebackend.common.enums.VehicleType.CAR,
            transmission = com.nhom15.datxebackend.common.enums.Transmission.AUTO,
            fareEstimate = 50_000,
            finalFare = 0,
            paymentMethod = PaymentMethod.CASH,
            status = com.nhom15.datxebackend.common.enums.RideStatus.COMPLETED,
            completedAt = java.time.Instant.now(),
        ).apply { id = rideId }
        Mockito.`when`(repo.findById(rideId)).thenReturn(Optional.of(ride))
        return repo
    }
}
