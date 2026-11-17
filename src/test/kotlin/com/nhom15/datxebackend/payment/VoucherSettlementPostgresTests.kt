package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.common.domain.Money
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.util.roundToNearestThousandVnd
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.repository.WalletRepository
import com.nhom15.datxebackend.payment.repository.WalletTransactionRepository
import com.nhom15.datxebackend.payment.service.PaymentMethodService
import com.nhom15.datxebackend.payment.service.PaymentService
import com.nhom15.datxebackend.payment.service.WalletService
import com.nhom15.datxebackend.ride.event.RideCompleted
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

/** Kiểm thử PostgreSQL thật: nhất quán thu nhập tài xế với voucher 0 VND, bù một phần, exact-net, CEILING rounding và retry. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class VoucherSettlementPostgresTests {
    private val schema = "vsettle_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var admin: JdbcTemplate
    private lateinit var jdbc: JdbcTemplate
    private lateinit var factory: LocalContainerEntityManagerFactoryBean
    private lateinit var tx: TransactionTemplate
    private lateinit var wallets: WalletService
    private lateinit var methodChanges: PaymentMethodService
    private lateinit var payments: PaymentService

    @BeforeEach
    fun setup() {
        val url = System.getenv("TEST_DB_URL")
        val user = System.getenv("TEST_DB_USER")
        val password = System.getenv("TEST_DB_PASSWORD")
        admin = JdbcTemplate(DriverManagerDataSource(url, user, password))
        admin.execute("CREATE SCHEMA $schema")
        val ds = DriverManagerDataSource(url, user, password).apply {
            connectionProperties = Properties().apply { setProperty("currentSchema", this@VoucherSettlementPostgresTests.schema) }
        }
        jdbc = JdbcTemplate(ds)
        Files.list(Path.of("src/main/resources/db/migration")).use { files ->
            files.filter { it.fileName.toString().endsWith(".sql") }
                .sorted(compareBy { it.fileName.toString().substringBefore("__").drop(1).toInt() })
                .forEach { jdbc.execute(Files.readString(it).removePrefix("\uFEFF")) }
        }
        factory = LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(ds)
            setPackagesToScan("com.nhom15.datxebackend")
            jpaVendorAdapter = HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none", "hibernate.jdbc.time_zone" to "UTC"))
            afterPropertiesSet()
        }
        val emf = factory.`object`!!
        tx = TransactionTemplate(JpaTransactionManager(emf))
        val repos = JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(emf))
        val driverRepo = repos.getRepository(DriverRepository::class.java)
        val customerRepo = repos.getRepository(CustomerRepository::class.java)
        val ledgerRepo = repos.getRepository(WalletTransactionRepository::class.java)
        val rideRepo = repos.getRepository(RideRepository::class.java)
        val publisher = Mockito.mock(ApplicationEventPublisher::class.java)
        wallets = WalletService(repos.getRepository(WalletRepository::class.java), ledgerRepo, driverRepo, customerRepo, 50_000)
        payments = PaymentService(jdbc, driverRepo, rideRepo, wallets, publisher)
        methodChanges = PaymentMethodService(rideRepo, payments, wallets, jdbc, publisher)
    }

    @AfterEach
    fun cleanup() {
        if (::factory.isInitialized) factory.destroy()
        if (::admin.isInitialized) admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
    }

    private fun seedBase(customerBalance: Long = 200_000L, driverCredit: Long = 100_000L, driverCash: Long = 0L) {
        jdbc.update("INSERT INTO drivers(id,firebase_uid,phone_number,status,verification_status) VALUES(7001,'vs-d','0901000001','BUSY','APPROVED')")
        jdbc.update("INSERT INTO customers(id,firebase_uid,phone_number,status) VALUES(7002,'vs-c','0901000002','ACTIVE')")
        jdbc.update("INSERT INTO vehicles(id,customer_id,plate_number,vehicle_type,transmission_type) VALUES(8001,7002,'30A-99999','CAR','AUTO')")
        jdbc.update("UPDATE wallets SET balance = ? WHERE user_id = 7002 AND wallet_type = 'CUSTOMER'", customerBalance)
        jdbc.update("UPDATE wallets SET balance = ? WHERE user_id = 7001 AND wallet_type = 'CREDIT'", driverCredit)
        jdbc.update("UPDATE wallets SET balance = ? WHERE user_id = 7001 AND wallet_type = 'CASH'", driverCash)
        jdbc.update("UPDATE customers SET wallet_balance = ? WHERE id = 7002", customerBalance)
        jdbc.update("UPDATE drivers SET credit_balance = ?, income_balance = ? WHERE id = 7001", driverCredit, driverCash)
    }

    private fun seedRide(
        rideId: Long,
        rawFare: Long,
        discount: Long,
        finalFare: Long,
        method: String,
    ) {
        jdbc.update(
            """INSERT INTO rides(id,customer_id,driver_id,vehicle_id,status,type,vehicle_type,transmission,
                                 fare_estimate,discount_amount,final_fare,payment_method,
                                 pickup_lat,pickup_lng,pickup_address,dropoff_lat,dropoff_lng,dropoff_address,completed_at)
               VALUES(?,?,7001,8001,'COMPLETED','INSTANT','CAR','AUTO',?,?,?,?,21.03,105.84,'Đón',21.04,105.85,'Trả',CURRENT_TIMESTAMP)""",
            rideId, 7002L, rawFare, discount, finalFare, method,
        )
    }

    private fun balance(userId: Long, type: String): Long =
        jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id = ? AND wallet_type = ?", Long::class.java, userId, type)!!

    @Test
    fun `0 vnd cash and wallet rides settle once and match daily summary and ride earnings`() {
        seedBase()
        seedRide(9101, 50_000, 50_000, 0, "CASH")
        seedRide(9102, 50_000, 50_000, 0, "WALLET")

        repeat(2) {
            tx.executeWithoutResult { payments.onRideCompleted(RideCompleted(9101, 7002, 7001, Money(0))) }
            tx.executeWithoutResult { payments.onRideCompleted(RideCompleted(9102, 7002, 7001, Money(0))) }
        }

        val driverActor = AuthUser(7001, "vs-d", Role.DRIVER, "Tài xế", null, null, "ONLINE")
        repeat(2) { tx.executeWithoutResult { payments.confirmCashReceived(driverActor, 9101) } }

        assertEquals(200_000L, balance(7002, "CUSTOMER"))
        assertEquals(100_000L, balance(7001, "CREDIT"))
        assertEquals(80_000L, balance(7001, "CASH"))
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM wallet_transactions", Long::class.java))

        val cashEarnings = payments.getDriverRideEarnings(7001, 9101)!!
        assertEquals(40_000L, cashEarnings.netAmount)
        assertEquals(0L, cashEarnings.commissionAmount)
        assertTrue(cashEarnings.creditedToCash)
        assertTrue(cashEarnings.paid)

        val walletEarnings = payments.getDriverRideEarnings(7001, 9102)!!
        assertEquals(40_000L, walletEarnings.netAmount)
        assertEquals(0L, walletEarnings.commissionAmount)
        assertTrue(walletEarnings.paid)

        assertEquals(80_000L, payments.getDriverDailySummary(7001).income)
    }

    @Test
    fun `exact net cash switches cleanly to wallet and subsidized cash refuses switch with conflict without 500 or partial debit`() {
        seedBase()
        seedRide(9201, 50_000, 30_000, 20_000, "CASH")
        seedRide(9202, 50_000, 10_000, 40_000, "CASH")

        tx.executeWithoutResult { payments.onRideCompleted(RideCompleted(9201, 7002, 7001, Money(20_000))) }
        tx.executeWithoutResult { payments.onRideCompleted(RideCompleted(9202, 7002, 7001, Money(40_000))) }
        assertEquals(20_000L, balance(7001, "CASH"))
        assertEquals(0L, payments.getDriverDailySummary(7001).income)

        val customerActor = AuthUser(7002, "vs-c", Role.CUSTOMER, "Khách", null, null, "ACTIVE")
        org.junit.jupiter.api.assertThrows<com.nhom15.datxebackend.common.exception.ConflictException> {
            tx.executeWithoutResult {
                methodChanges.change(customerActor, 9201, PaymentMethod.WALLET, PaymentMethod.CASH, "voucher-switch-sub")
            }
        }
        assertEquals(200_000L, balance(7002, "CUSTOMER"))
        assertEquals(20_000L, balance(7001, "CASH"))

        repeat(2) {
            tx.executeWithoutResult {
                methodChanges.change(customerActor, 9202, PaymentMethod.WALLET, PaymentMethod.CASH, "voucher-switch-exact")
            }
        }

        assertEquals(160_000L, balance(7002, "CUSTOMER"))
        assertEquals(100_000L, balance(7001, "CREDIT"))
        assertEquals(60_000L, balance(7001, "CASH"))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM payment_method_changes", Long::class.java))
        assertEquals(3L, jdbc.queryForObject("SELECT count(*) FROM wallet_transactions", Long::class.java))
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE ride_id = 9202", Long::class.java))

        val earnings = payments.getDriverRideEarnings(7001, 9202)!!
        assertEquals(40_000L, earnings.netAmount)
        assertEquals(0L, earnings.commissionAmount)
        assertEquals(40_000L, payments.getDriverDailySummary(7001).income)
    }

    @Test
    fun `ceiling rounding keeps surplus for platform and driver receives exact 80 percent of raw estimate`() {
        seedBase()
        val rawFare = 31_250L
        val payable = rawFare.roundToNearestThousandVnd()
        assertEquals(32_000L, payable)
        assertEquals(25_000L, WalletService.driverNetEarning(rawFare))

        seedRide(9301, rawFare, 0, payable, "CASH")
        seedRide(9302, rawFare, 0, payable, "WALLET")
        tx.executeWithoutResult { payments.onRideCompleted(RideCompleted(9301, 7002, 7001, Money(payable))) }
        tx.executeWithoutResult { payments.onRideCompleted(RideCompleted(9302, 7002, 7001, Money(payable))) }

        val driverActor = AuthUser(7001, "vs-d", Role.DRIVER, "Tài xế", null, null, "ONLINE")
        tx.executeWithoutResult { payments.confirmCashReceived(driverActor, 9301) }

        assertEquals(168_000L, balance(7002, "CUSTOMER"))
        assertEquals(93_000L, balance(7001, "CREDIT"))
        assertEquals(25_000L, balance(7001, "CASH"))

        assertEquals(25_000L, payments.getDriverRideEarnings(7001, 9301)!!.netAmount)
        assertEquals(7_000L, payments.getDriverRideEarnings(7001, 9301)!!.commissionAmount)
        assertEquals(25_000L, payments.getDriverRideEarnings(7001, 9302)!!.netAmount)
        assertEquals(7_000L, payments.getDriverRideEarnings(7001, 9302)!!.commissionAmount)
        assertEquals(50_000L, payments.getDriverDailySummary(7001).income)
    }

    @Test
    fun `exact net cash voucher ride with zero fee and zero subsidy still reports daily income and earnings`() {
        seedBase()
        seedRide(9401, 50_000, 10_000, 40_000, "CASH")

        tx.executeWithoutResult { payments.onRideCompleted(RideCompleted(9401, 7002, 7001, Money(40_000))) }
        val driverActor = AuthUser(7001, "vs-d", Role.DRIVER, "Tài xế", null, null, "ONLINE")
        tx.executeWithoutResult { payments.confirmCashReceived(driverActor, 9401) }

        assertEquals(100_000L, balance(7001, "CREDIT"))
        assertEquals(0L, balance(7001, "CASH"))
        val earnings = payments.getDriverRideEarnings(7001, 9401)!!
        assertEquals(40_000L, earnings.netAmount)
        assertEquals(0L, earnings.commissionAmount)
        assertEquals(40_000L, payments.getDriverDailySummary(7001).income)
    }
}
