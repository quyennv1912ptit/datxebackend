package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.common.domain.Money
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.payment.event.PaymentUpdated
import com.nhom15.datxebackend.payment.service.PaymentMethodService
import com.nhom15.datxebackend.payment.service.PaymentService
import com.nhom15.datxebackend.payment.service.WalletService
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.event.RideCompleted
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.voucher.service.PromoBudgetService
import com.nhom15.datxebackend.voucher.service.VoucherService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.Optional
import java.util.Properties
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class P2GroupBFinancialIntegrityPostgresTests {

    private fun withIsolatedSchema(block: (DriverManagerDataSource, String) -> Unit) {
        val url = System.getenv("TEST_DB_URL")
        val user = System.getenv("TEST_DB_USER")
        val pass = System.getenv("TEST_DB_PASSWORD")
        val schema = "p2b_" + UUID.randomUUID().toString().replace("-", "").take(16)
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
    fun `F-005 PaymentMethodService rejects switching CASH to WALLET when ride used a CASH-only voucher`() {
        withIsolatedSchema { ds, _ ->
            val jdbc = JdbcTemplate(ds)
            jdbc.update("INSERT INTO customers(id,firebase_uid,status,wallet_balance) VALUES(1,'c1','ACTIVE',500000)")
            jdbc.update("INSERT INTO drivers(id,firebase_uid,phone_number,status,credit_balance,income_balance) VALUES(10,'d1','+8490001','ONLINE',200000,0)")
            jdbc.update("INSERT INTO vehicles(id,customer_id,plate_number,vehicle_type,transmission_type) VALUES(5,1,'30A-1','CAR','AUTO')")
            jdbc.update(
                """INSERT INTO rides(id,customer_id,driver_id,vehicle_id,status,type,vehicle_type,transmission,fare_estimate,final_fare,discount_amount,
                   payment_method,pickup_lat,pickup_lng,pickup_address,dropoff_lat,dropoff_lng,dropoff_address)
                   VALUES(99,1,10,5,'COMPLETED','INSTANT','CAR','AUTO',100000,80000,20000,'CASH',21.0,105.8,'A',21.01,105.81,'B')""",
            )
            jdbc.update(
                """INSERT INTO payments(ride_id,customer_id,driver_id,amount,method,status)
                   VALUES(99,1,10,80000,'CASH','PENDING')""",
            )
            jdbc.update(
                """INSERT INTO vouchers(id,code,discount_type,value,minimum_order_amount,valid_from,valid_to,active,usage_limit,usage_count,allowed_payment_method)
                   VALUES(50,'CASHONLY','FIXED',20000,0,CURRENT_TIMESTAMP - interval '1 day', CURRENT_TIMESTAMP + interval '1 day', TRUE, 10, 1, 'CASH')""",
            )
            jdbc.update("INSERT INTO voucher_usages(voucher_id,customer_id,ride_id) VALUES(50,1,99)")

            val ride = Mockito.mock(Ride::class.java)
            Mockito.`when`(ride.id).thenReturn(99L)
            Mockito.`when`(ride.customerId).thenReturn(1L)
            Mockito.`when`(ride.driverId).thenReturn(10L)
            Mockito.`when`(ride.status).thenReturn(RideStatus.COMPLETED)
            Mockito.`when`(ride.paymentMethod).thenReturn(PaymentMethod.CASH)
            Mockito.`when`(ride.fareEstimate).thenReturn(100_000L)

            val rideRepo = Mockito.mock(RideRepository::class.java)
            val driverRepo = Mockito.mock(com.nhom15.datxebackend.driver.repository.DriverRepository::class.java)
            Mockito.`when`(rideRepo.findByIdForUpdate(99L)).thenReturn(Optional.of(ride))
            val wallets = Mockito.mock(WalletService::class.java)
            val events = Mockito.mock(ApplicationEventPublisher::class.java)
            val payments = PaymentService(jdbc, driverRepo, rideRepo, wallets, events)
            val service = PaymentMethodService(rideRepo, payments, wallets, jdbc, events)

            val customer = AuthUser(1L, "c1", Role.CUSTOMER, "Cust", null, null, "ACTIVE")
            assertThatThrownBy {
                service.change(customer, 99L, PaymentMethod.WALLET, PaymentMethod.CASH, "switch-key-1")
            }.isInstanceOf(BadRequestException::class.java)
        }
    }

    @Test
    fun `F-006 PaymentService publishes PaymentUpdated when WALLET settlement falls back to CASH`() {
        val rideRepo = Mockito.mock(RideRepository::class.java)
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        val wallets = Mockito.mock(WalletService::class.java)
        val events = Mockito.mock(ApplicationEventPublisher::class.java)

        val ride = Mockito.mock(Ride::class.java)
        Mockito.`when`(ride.id).thenReturn(99L)
        Mockito.`when`(ride.customerId).thenReturn(1L)
        Mockito.`when`(ride.driverId).thenReturn(10L)
        Mockito.`when`(ride.status).thenReturn(RideStatus.COMPLETED)
        Mockito.`when`(ride.finalFare).thenReturn(100_000L)
        Mockito.`when`(ride.fareEstimate).thenReturn(100_000L)
        Mockito.`when`(ride.paymentMethod).thenReturn(PaymentMethod.WALLET)
        Mockito.`when`(rideRepo.findByIdForUpdate(99L)).thenReturn(Optional.of(ride))
        Mockito.doReturn(1).`when`(jdbc).update(anyString(), eq(99L))
        Mockito.`when`(wallets.settle(99L, 1L, 10L, 100_000L, PaymentMethod.WALLET, 100_000L))
            .thenReturn(PaymentMethod.CASH)

        val driverRepo = Mockito.mock(com.nhom15.datxebackend.driver.repository.DriverRepository::class.java)
        val service = PaymentService(jdbc, driverRepo, rideRepo, wallets, events)
        service.onRideCompleted(RideCompleted(99L, 1L, 10L, Money(100_000L)))

        val eventCaptor = ArgumentCaptor.forClass(Any::class.java)
        Mockito.verify(events).publishEvent(eventCaptor.capture())
        val published = eventCaptor.value as PaymentUpdated
        assertThat(published.rideId).isEqualTo(99L)
        assertThat(published.fromMethod).isEqualTo(PaymentMethod.WALLET)
        assertThat(published.method).isEqualTo(PaymentMethod.CASH)
        assertThat(published.status).isEqualTo("PENDING")
    }

    @Test
    fun `F-009 PromoBudgetService setLimit uses atomic cap guard and F-012 campaign remaining subtracts released_amount`() {
        withIsolatedSchema { ds, _ ->
            val jdbc = JdbcTemplate(ds)
            val budgetService = PromoBudgetService(jdbc)
            val voucherService = VoucherService(jdbc)

            // F-009: setLimit below committed (held + spent + issuance_reserved) must fail atomically
            jdbc.update("UPDATE promo_budget SET budget_limit = 500000, held_amount = 100000, spent_amount = 50000, issuance_reserved = 100000 WHERE id = 1")
            assertThatThrownBy { budgetService.setLimit(200_000L) }
                .isInstanceOf(BadRequestException::class.java)

            // F-012: campaign with budget_limit=100_000, released_amount=90_000, forecast=20_000 has only 10_000 remaining -> NOT claimable
            jdbc.update("INSERT INTO customers(id,firebase_uid,status,wallet_balance) VALUES(1,'c1','ACTIVE',0)")
            jdbc.update(
                """INSERT INTO vouchers(id,code,campaign_title,description,discount_type,value,max_discount,minimum_order_amount,
                   usage_limit,usage_count,campaign_claim_count,campaign_budget_limit,forecast_reserve_per_use,
                   active,valid_from,valid_to,source)
                   VALUES(700,'CAMP1','Camp','Desc','FIXED',20000,20000,0,10,0,0,100000,20000,TRUE,
                          CURRENT_TIMESTAMP - interval '1 day', CURRENT_TIMESTAMP + interval '5 days', 'PUBLIC_CODE')""",
            )
            jdbc.update(
                "INSERT INTO voucher_campaign_budget(campaign_id,budget_limit,held_amount,spent_amount,released_amount) VALUES(700,100000,0,0,90000)",
            )

            val actor = AuthUser(1L, "c1", Role.CUSTOMER, "Cust", null, null, "ACTIVE")
            assertThat(voucherService.listClaimableCampaigns(actor)).isEmpty()
            assertThatThrownBy { voucherService.claimCampaign(actor, 700L) }
                .isInstanceOf(BadRequestException::class.java)
        }
    }
}
