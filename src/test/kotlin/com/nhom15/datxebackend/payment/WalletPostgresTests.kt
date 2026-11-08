package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.entity.*
import com.nhom15.datxebackend.payment.repository.*
import com.nhom15.datxebackend.payment.service.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.*
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import java.util.concurrent.*

/** Uses a unique schema; never modifies application tables. Supply a disposable PostgreSQL database. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class WalletPostgresTests {
    private val schema = "wallet_test_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var admin: JdbcTemplate
    private lateinit var jdbc: JdbcTemplate
    private lateinit var factory: LocalContainerEntityManagerFactoryBean
    private lateinit var tx: TransactionTemplate
    private lateinit var wallets: WalletService
    private lateinit var methodChanges: PaymentMethodService
    private lateinit var payments: PaymentService
    private lateinit var withdrawals: WithdrawalService

    @BeforeEach fun setup() {
        val url = System.getenv("TEST_DB_URL")
        val user = System.getenv("TEST_DB_USER")
        val password = System.getenv("TEST_DB_PASSWORD")
        admin = JdbcTemplate(DriverManagerDataSource(url, user, password))
        admin.execute("CREATE SCHEMA $schema")
        val dataSource = DriverManagerDataSource(url, user, password).apply {
            connectionProperties = Properties().apply { setProperty("currentSchema", this@WalletPostgresTests.schema) }
        }
        jdbc = JdbcTemplate(dataSource)
        Files.list(Path.of("src/main/resources/db/migration")).use { files ->
            files.filter { it.fileName.toString().endsWith(".sql") }
                .sorted(compareBy { it.fileName.toString().substringBefore("__").drop(1).toInt() })
                .forEach { jdbc.execute(Files.readString(it)) }
        }
        factory = LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(dataSource)
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
        wallets = WalletService(repos.getRepository(WalletRepository::class.java), ledgerRepo, driverRepo, customerRepo, 50_000)
        payments = PaymentService(jdbc, driverRepo, repos.getRepository(com.nhom15.datxebackend.ride.repository.RideRepository::class.java), wallets, org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher::class.java))
        methodChanges = PaymentMethodService(repos.getRepository(com.nhom15.datxebackend.ride.repository.RideRepository::class.java), payments, wallets, jdbc, org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher::class.java))
        withdrawals = WithdrawalService(repos.getRepository(WithdrawalRepository::class.java), ledgerRepo, driverRepo, wallets, org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher::class.java))
        jdbc.update("INSERT INTO drivers(id,firebase_uid,phone_number,status,credit_balance,income_balance) VALUES (7001,'wallet-test','wallet-test','ONLINE',100000,100000)")
    }

    @AfterEach fun cleanup() {
        if (::factory.isInitialized) factory.destroy()
        if (::admin.isInitialized) admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
    }

    private fun concurrently(action: () -> Unit): List<Boolean> {
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val jobs = (1..2).map { pool.submit<Boolean> {
                start.await()
                runCatching { tx.executeWithoutResult { action() } }.isSuccess
            } }
            start.countDown()
            return jobs.map { it.get(20, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }

    @Test fun `concurrent withdrawals cannot overdraw and failed request leaves no ledger`() {
        val outcomes = concurrently { withdrawals.create(7001, 80_000, UUID.randomUUID().toString()) }
        assertEquals(1, outcomes.count { it })
        assertEquals(20_000L, jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id=7001 AND wallet_type='CASH'", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM withdrawal_requests", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM wallet_transactions", Long::class.java))
    }

    @Test fun `simultaneous rejects refund exactly once`() {
        val id = tx.execute { withdrawals.create(7001, 80_000, "hold-1").id }
        assertEquals(1, concurrently { withdrawals.review(id, 1, WithdrawalAction.REJECT, "Sai số tài khoản") }.count { it })
        assertEquals(100_000L, jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id=7001 AND wallet_type='CASH'", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM wallet_transactions WHERE transaction_type='REFUND'", Long::class.java))
    }

    @Test fun `exception after balance mutation rolls back balance and ledger together`() {
        tx.executeWithoutResult { wallets.balances(7001, WalletOwner.DRIVER) }
        assertThrows(IllegalStateException::class.java) {
            tx.executeWithoutResult {
                wallets.topUp(7001, WalletOwner.DRIVER, WalletType.CASH, 50_000)
                error("Injected failure after ledger write")
            }
        }
        assertEquals(100_000L, jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id=7001 AND wallet_type='CASH'", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM wallet_transactions", Long::class.java))
    }

    private fun seedRide(method: String, balance: Long = 150_000, status: String = "COMPLETED") {
        jdbc.update("INSERT INTO customers(id,firebase_uid,status,wallet_balance) VALUES(7002,'wallet-customer','ACTIVE',?)", balance)
        jdbc.update("INSERT INTO vehicles(id,customer_id,plate_number,vehicle_type,transmission_type) VALUES(8001,7002,'TEST-001','CAR','AUTO')")
        jdbc.update("""INSERT INTO rides(id,customer_id,driver_id,vehicle_id,status,type,vehicle_type,transmission,
            fare_estimate,final_fare,payment_method,completed_at,pickup_lat,pickup_lng,pickup_address,dropoff_lat,dropoff_lng,dropoff_address)
            VALUES(9001,7002,7001,8001,?,'INSTANT','CAR','AUTO',100000,100000,?,CURRENT_TIMESTAMP,21.03,105.84,'Đón',21.04,105.85,'Trả')""", status, method)
    }
    private fun completedEvent() = com.nhom15.datxebackend.ride.event.RideCompleted(9001,7002,7001,com.nhom15.datxebackend.common.domain.Money(100_000))
    private fun balance(user: Long, type: String) = jdbc.queryForObject("SELECT balance FROM wallets WHERE user_id=? AND wallet_type=?", Long::class.java, user, type)

    @Test fun `wallet payment settles exactly once and earnings come from the ride ledger`() {
        seedRide("WALLET")
        repeat(2) { tx.executeWithoutResult { payments.onRideCompleted(completedEvent()) } }
        assertEquals(50_000L, balance(7002,"CUSTOMER"))
        assertEquals(180_000L, balance(7001,"CASH"))
        assertEquals(100_000L, balance(7001,"CREDIT"))
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM wallet_transactions",Long::class.java))
        assertEquals("PAID", payments.getRidePayment(9001)!!.status)
        assertEquals(80_000L, payments.getDriverRideEarnings(7001,9001)!!.netAmount)
        assertEquals(80_000L, payments.getDriverDailySummary(7001).income)
    }

    @Test fun `cash commission is held once and cash confirmation never debits the wallet`() {
        seedRide("CASH")
        tx.executeWithoutResult { payments.onRideCompleted(completedEvent()) }
        assertEquals(80_000L, balance(7001,"CREDIT"))
        assertEquals(100_000L, balance(7001,"CASH"))
        assertEquals(150_000L, balance(7002,"CUSTOMER"))
        assertEquals(0L, payments.getDriverDailySummary(7001).income)
        val actor = com.nhom15.datxebackend.common.security.AuthUser(7001,"test",com.nhom15.datxebackend.common.enums.Role.DRIVER,"Test",null,null,"ONLINE")
        repeat(2) { tx.executeWithoutResult { payments.confirmCashReceived(actor,9001) } }
        assertEquals(80_000L, balance(7001,"CREDIT"))
        assertEquals(100_000L, balance(7001,"CASH"))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM wallet_transactions",Long::class.java))
        assertEquals(80_000L, payments.getDriverDailySummary(7001).income)
        assertTrue(payments.getDriverRideEarnings(7001,9001)!!.paid)
    }

    @Test fun `wallet shortfall at completion falls back to cash instead of blocking the ride`() {
        seedRide("WALLET",10_000,"VEHICLE_RETURNED")
        tx.executeWithoutResult {
            jdbc.update("UPDATE rides SET status='COMPLETED' WHERE id=9001")
            payments.onRideCompleted(completedEvent())
        }
        assertEquals("COMPLETED",jdbc.queryForObject("SELECT status FROM rides WHERE id=9001",String::class.java))
        assertEquals("CASH",jdbc.queryForObject("SELECT payment_method FROM rides WHERE id=9001",String::class.java))
        val payment = payments.getRidePayment(9001)!!
        assertEquals("CASH",payment.method)
        assertEquals("PENDING",payment.status)
        assertEquals(10_000L,balance(7002,"CUSTOMER"))
        assertEquals(80_000L,balance(7001,"CREDIT"))
        assertEquals(100_000L,balance(7001,"CASH"))
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM wallet_transactions",Long::class.java))
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM payments",Long::class.java))
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM ride_wallet_settlements",Long::class.java))
        assertEquals(0L,payments.getDriverDailySummary(7001).income)
        assertTrue(jdbc.queryForObject("SELECT description FROM wallet_transactions",String::class.java)!!.contains("tự động thu tiền mặt"))
    }

    @Test fun `cash shortfall persists negative credit and blocked driver in PostgreSQL`() {
        seedRide("CASH")
        jdbc.update("UPDATE wallets SET balance=10000 WHERE user_id=7001 AND wallet_type='CREDIT'")
        jdbc.update("UPDATE drivers SET credit_balance=10000 WHERE id=7001")
        tx.executeWithoutResult { payments.onRideCompleted(completedEvent()) }
        assertEquals(-10_000L,balance(7001,"CREDIT"))
        assertEquals("BLOCKED",jdbc.queryForObject("SELECT status FROM drivers WHERE id=7001",String::class.java))
    }

    private fun customerActor(id: Long = 7002) = com.nhom15.datxebackend.common.security.AuthUser(id,"customer",com.nhom15.datxebackend.common.enums.Role.CUSTOMER,"Test",null,null,"ACTIVE")
    private val cashMethod get() = com.nhom15.datxebackend.common.enums.PaymentMethod.CASH
    private val walletMethod get() = com.nhom15.datxebackend.common.enums.PaymentMethod.WALLET

    @Test fun `pending cash changes to wallet atomically refunds credit and retries without a second charge`() {
        seedRide("CASH")
        tx.executeWithoutResult { payments.onRideCompleted(completedEvent()) }
        repeat(2) { tx.executeWithoutResult { methodChanges.change(customerActor(),9001,walletMethod,cashMethod,"switch-1") } }
        assertEquals(50_000L,balance(7002,"CUSTOMER"))
        assertEquals(100_000L,balance(7001,"CREDIT"))
        assertEquals(180_000L,balance(7001,"CASH"))
        assertEquals("WALLET",payments.getRidePayment(9001)!!.method)
        assertEquals("PAID",payments.getRidePayment(9001)!!.status)
        assertEquals("WALLET",jdbc.queryForObject("SELECT payment_method FROM rides WHERE id=9001",String::class.java))
        assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM payment_method_changes",Long::class.java))
        assertEquals(4L,jdbc.queryForObject("SELECT count(*) FROM wallet_transactions",Long::class.java))
        assertEquals(80_000L,payments.getDriverDailySummary(7001).income)
    }

    @Test fun `insufficient customer wallet cannot partially refund credit or change method`() {
        seedRide("CASH",10_000)
        tx.executeWithoutResult { payments.onRideCompleted(completedEvent()) }
        assertThrows(com.nhom15.datxebackend.payment.exception.InsufficientCustomerBalanceException::class.java) {
            tx.executeWithoutResult { methodChanges.change(customerActor(),9001,walletMethod,cashMethod,"switch-fail") }
        }
        assertEquals(10_000L,balance(7002,"CUSTOMER"))
        assertEquals(80_000L,balance(7001,"CREDIT"))
        assertEquals(100_000L,balance(7001,"CASH"))
        assertEquals("CASH",payments.getRidePayment(9001)!!.method)
        assertEquals("PENDING",payments.getRidePayment(9001)!!.status)
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM payment_method_changes",Long::class.java))
    }

    @Test fun `already paid wallet and non owner cannot switch payment`() {
        seedRide("WALLET")
        tx.executeWithoutResult { payments.onRideCompleted(completedEvent()) }
        assertThrows(com.nhom15.datxebackend.common.exception.ConflictException::class.java) {
            tx.executeWithoutResult { methodChanges.change(customerActor(),9001,cashMethod,walletMethod,"paid") }
        }
        assertThrows(com.nhom15.datxebackend.common.exception.NotFoundException::class.java) {
            tx.executeWithoutResult { methodChanges.change(customerActor(9999),9001,cashMethod,walletMethod,"other-user") }
        }
        assertEquals(50_000L,balance(7002,"CUSTOMER"))
        assertEquals(180_000L,balance(7001,"CASH"))
    }

    @Test fun `pending uncharged wallet can switch to cash but cannot reverse settled wallet money`() {
        seedRide("WALLET")
        jdbc.update("INSERT INTO payments(ride_id,customer_id,driver_id,amount,method,status) VALUES(9001,7002,7001,100000,'WALLET','PENDING')")
        tx.executeWithoutResult { methodChanges.change(customerActor(),9001,cashMethod,walletMethod,"to-cash") }
        assertEquals("CASH",payments.getRidePayment(9001)!!.method)
        assertEquals("PENDING",payments.getRidePayment(9001)!!.status)
        assertEquals(80_000L,balance(7001,"CREDIT"))
        assertEquals(150_000L,balance(7002,"CUSTOMER"))
        assertEquals(100_000L,balance(7001,"CASH"))
    }

    @Test fun `cash confirmation racing method switch can only collect through one method`() {
        seedRide("CASH")
        tx.executeWithoutResult { payments.onRideCompleted(completedEvent()) }
        val driver = customerActor(7001).copy(role=com.nhom15.datxebackend.common.enums.Role.DRIVER)
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val operations = listOf<() -> Unit>(
                { payments.confirmCashReceived(driver,9001); Unit },
                { methodChanges.change(customerActor(),9001,walletMethod,cashMethod,"race"); Unit })
            val jobs = operations.map { operation -> pool.submit<Boolean> { start.await(); runCatching { tx.executeWithoutResult { operation() } }.isSuccess } }
            start.countDown()
            assertEquals(1,jobs.map { it.get(20,TimeUnit.SECONDS) }.count { it })
            val payment = payments.getRidePayment(9001)!!
            assertEquals("PAID",payment.status)
            if (payment.method == "CASH") {
                assertEquals(150_000L,balance(7002,"CUSTOMER"))
                assertEquals(80_000L,balance(7001,"CREDIT"))
                assertEquals(100_000L,balance(7001,"CASH"))
            } else {
                assertEquals(50_000L,balance(7002,"CUSTOMER"))
                assertEquals(100_000L,balance(7001,"CREDIT"))
                assertEquals(180_000L,balance(7001,"CASH"))
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun `admin withdrawal queue filters by status and exposes driver bank details`() {
        jdbc.update("UPDATE drivers SET bank_name='Vietcombank', bank_account_number='0123456789' WHERE id=7001")
        tx.execute { withdrawals.create(7001, 50_000, "admin-1") }

        val pending = withdrawals.adminList(WithdrawalStatus.PENDING, 0, 20)

        assertEquals(1, pending.content.size)
        assertEquals(50_000L, pending.content.single().amount)
        assertEquals("Vietcombank", pending.content.single().bankName)
        assertEquals("0123456789", pending.content.single().bankAccountNumber)
        assertTrue(withdrawals.adminList(WithdrawalStatus.REJECTED, 0, 20).content.isEmpty())
        assertEquals(1, withdrawals.adminList(null, 0, 20).content.size)
    }
}
