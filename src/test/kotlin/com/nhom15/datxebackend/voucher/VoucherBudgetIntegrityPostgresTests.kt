package com.nhom15.datxebackend.voucher

import com.nhom15.datxebackend.voucher.service.PromoBudgetService
import com.nhom15.datxebackend.voucher.service.VoucherGrantService
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.Properties
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class VoucherBudgetIntegrityPostgresTests {

    @Test
    fun `campaign claim and welcome reward are independent and welcome remains idempotent`() {
        withIsolatedSchema { ds, _ ->
            val jdbc = JdbcTemplate(ds)
            val grant = VoucherGrantService(jdbc)
            val transaction = TransactionTemplate(DataSourceTransactionManager(ds))
            val customer = jdbc.queryForObject(
                "INSERT INTO customers(firebase_uid, full_name, phone_number, status) VALUES ('welcome-test', 'Customer', '0900000101', 'ACTIVE') RETURNING id",
                Long::class.java,
            )!!
            val campaign = jdbc.queryForObject(
                """INSERT INTO vouchers(code, discount_type, value, usage_limit, active, valid_from, valid_to)
                   VALUES ('WELCOME-TEST', 'PERCENT', 10, 10, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + interval '1 day') RETURNING id""",
                Long::class.java,
            )!!
            jdbc.update(
                """INSERT INTO vouchers(source, owner_customer_id, discount_type, value, usage_limit, active,
                       valid_from, valid_to, milestone, campaign_id)
                   VALUES ('SYSTEM_REWARD', ?, 'PERCENT', 10, 1, TRUE,
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + interval '1 day', 0, ?)""",
                customer, campaign,
            )

            assertThat(transaction.execute { grant.grantWelcome(customer) }).isTrue()
            assertThat(transaction.execute { grant.grantWelcome(customer) }).isFalse()
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vouchers WHERE owner_customer_id = ?", Long::class.java, customer))
                .isEqualTo(2)
        }
    }

    private fun withIsolatedSchema(block: (DriverManagerDataSource, String) -> Unit) {
        val url = System.getenv("TEST_DB_URL")
        val user = System.getenv("TEST_DB_USER")
        val pass = System.getenv("TEST_DB_PASSWORD")
        val schema = "f010_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val admin = JdbcTemplate(DriverManagerDataSource(url, user, pass))
        admin.execute("CREATE SCHEMA $schema")
        try {
            val ds = DriverManagerDataSource(url, user, pass).apply {
                connectionProperties = Properties().apply { setProperty("currentSchema", schema) }
            }
            Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate()
            block(ds, schema)
        } finally {
            admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    @Test
    fun `F-010 repeated hold and release of non-campaign welcome voucher preserves issuance_reserved invariant and blocks SPEND after RELEASE`() {
        withIsolatedSchema { ds, _ ->
            val jdbc = JdbcTemplate(ds)
            val budgetService = PromoBudgetService(jdbc)

            // Initialize promo_budget with 1_000_000 limit and 25_000 issuance_reserved (forecast = 50_000 / 2)
            jdbc.update(
                "UPDATE promo_budget SET budget_limit = 1000000, held_amount = 0, spent_amount = 0, issuance_reserved = 25000 WHERE id = 1",
            )
            val customerId = jdbc.queryForObject(
                """INSERT INTO customers (firebase_uid, full_name, phone_number, status, wallet_balance, created_at, updated_at)
                   VALUES ('uid-v-1', 'Voucher User', '+84900000010', 'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                   RETURNING id""",
                Long::class.java,
            )!!
            val vehicleId = jdbc.queryForObject(
                """INSERT INTO vehicles (customer_id, vehicle_type, transmission_type, plate_number, created_at, updated_at)
                   VALUES (?, 'CAR', 'AUTO', '30A-12345', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                   RETURNING id""",
                Long::class.java,
                customerId,
            )!!
            val voucherId = jdbc.queryForObject(
                """INSERT INTO vouchers (source, owner_customer_id, discount_type, value, max_discount, minimum_order_amount,
                       usage_limit, usage_count, active, valid_from, valid_to, milestone)
                   VALUES ('SYSTEM_REWARD', ?, 'PERCENT', 50, 50000, 0, 1, 0, TRUE,
                           CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP + INTERVAL '30 days', 0)
                   RETURNING id""",
                Long::class.java,
                customerId,
            )!!

            // Run 5 book -> cancel cycles with actual discount 50_000 (forecast = 25_000)
            var lastRideId = 0L
            repeat(5) { idx ->
                val rideId = jdbc.queryForObject(
                    """INSERT INTO rides (customer_id, vehicle_id, status, type, pickup_lat, pickup_lng, pickup_address,
                           dropoff_lat, dropoff_lng, dropoff_address, vehicle_type, transmission, fare_estimate,
                           discount_amount, payment_method, version, created_at, updated_at)
                       VALUES (?, ?, 'PENDING', 'INSTANT', 21.0, 105.8, 'A', 21.01, 105.81, 'B',
                               'CAR', 'AUTO', 100000, 50000, 'CASH', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                       RETURNING id""",
                    Long::class.java,
                    customerId,
                    vehicleId,
                )!!
                lastRideId = rideId
                budgetService.hold(rideId, voucherId, 50_000L)
                budgetService.release(rideId)
            }

            val afterCycles = jdbc.queryForMap("SELECT held_amount, spent_amount, issuance_reserved FROM promo_budget WHERE id = 1")
            assertThat(afterCycles["held_amount"]).isEqualTo(0L)
            assertThat(afterCycles["spent_amount"]).isEqualTo(0L)
            assertThat(afterCycles["issuance_reserved"])
                .describedAs("issuance_reserved must stay 25_000 (not inflate by 5 * 25_000 = 125_000!)")
                .isEqualTo(25_000L)

            // F-002 guard: calling spend() after release() on the same ride must be a no-op (no SPEND alongside RELEASE)
            budgetService.spend(lastRideId)
            val spendRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM promo_budget_ledger WHERE ride_id = ? AND action = 'SPEND'",
                Long::class.java,
                lastRideId,
            )
            assertThat(spendRows).isEqualTo(0L)
        }
    }

    @Test
    fun `F-002 adminAssignDriver reviving NO_DRIVER_FOUND zeros discountAmount so released voucher cannot double-redeem`() {
        val rideRepo = org.mockito.Mockito.mock(com.nhom15.datxebackend.ride.repository.RideRepository::class.java)
        val driverRepo = org.mockito.Mockito.mock(com.nhom15.datxebackend.driver.repository.DriverRepository::class.java)
        val eligibility = org.mockito.Mockito.mock(com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility::class.java)
        val wallets = org.mockito.Mockito.mock(com.nhom15.datxebackend.payment.service.WalletService::class.java)

        val ride = com.nhom15.datxebackend.ride.entity.Ride(
            customerId = 1L,
            driverId = null,
            vehicleId = 5L,
            status = com.nhom15.datxebackend.common.enums.RideStatus.NO_DRIVER_FOUND,
            type = com.nhom15.datxebackend.ride.entity.RideType.INSTANT,
            pickup = com.nhom15.datxebackend.common.domain.Location(com.nhom15.datxebackend.common.domain.GeoPoint(21.0, 105.8), "A"),
            dropoff = com.nhom15.datxebackend.common.domain.Location(com.nhom15.datxebackend.common.domain.GeoPoint(21.01, 105.81), "B"),
            vehicleType = com.nhom15.datxebackend.common.enums.VehicleType.CAR,
            transmission = com.nhom15.datxebackend.common.enums.Transmission.AUTO,
            fareEstimate = 100_000L,
            discountAmount = 50_000L,
            paymentMethod = com.nhom15.datxebackend.common.enums.PaymentMethod.CASH,
        ).apply { id = 77L }
        val driver = com.nhom15.datxebackend.driver.entity.Driver().apply {
            id = 20L
            firebaseUid = "drv-20"
            status = com.nhom15.datxebackend.common.enums.DriverStatus.ONLINE
        }

        org.mockito.Mockito.`when`(rideRepo.findByIdForUpdate(77L)).thenReturn(java.util.Optional.of(ride))
        org.mockito.Mockito.`when`(rideRepo.compareAndSetStatus(77L, "NO_DRIVER_FOUND", "DRIVER_ASSIGNED", 0L)).thenReturn(1)
        org.mockito.Mockito.`when`(driverRepo.findByIdForUpdate(20L)).thenReturn(java.util.Optional.of(driver))
        org.mockito.Mockito.doReturn(true).`when`(eligibility).mayDriveForRide(
            org.mockito.Mockito.any() ?: driver,
            org.mockito.Mockito.any() ?: ride,
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.anyBoolean(),
            org.mockito.Mockito.any() ?: java.time.Instant.now(),
        )

        val stateMachine = com.nhom15.datxebackend.ride.service.state.RideStateMachine(
            rideRepository = rideRepo,
            historyRepository = org.mockito.Mockito.mock(com.nhom15.datxebackend.ride.repository.RideStatusHistoryRepository::class.java),
            eventPublisher = org.mockito.Mockito.mock(com.nhom15.datxebackend.common.event.EventPublisher::class.java),
            reportRepository = org.mockito.Mockito.mock(com.nhom15.datxebackend.ride.repository.VehicleHandoverReportRepository::class.java),
            conversationService = org.mockito.Mockito.mock(com.nhom15.datxebackend.chat.service.ConversationService::class.java),
            matching = com.nhom15.datxebackend.config.properties.MatchingProperties(),
            dispatchOutbox = org.mockito.Mockito.mock(com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository::class.java),
            dispatchOffers = org.mockito.Mockito.mock(com.nhom15.datxebackend.ride.repository.DispatchOfferRepository::class.java),
            drivers = driverRepo,
            wallets = wallets,
            vouchers = org.mockito.Mockito.mock(com.nhom15.datxebackend.voucher.service.VoucherService::class.java),
            scheduled = com.nhom15.datxebackend.config.properties.ScheduledRideProperties(),
            clock = java.time.Clock.systemUTC(),
            verification = eligibility,
        )

        val (assigned, _) = stateMachine.adminAssignDriver(77L, 20L, 1L)
        assertThat(assigned.discountAmount).isEqualTo(0L)
        org.mockito.Mockito.verify(wallets).requireEligibleDriver(20L, 100_000L, 100_000L)
    }
}
