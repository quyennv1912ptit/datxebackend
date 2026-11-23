package com.nhom15.datxebackend.voucher

import com.nhom15.datxebackend.voucher.service.PromoBudgetService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

/** PostgreSQL thật: kiểm tra số dư quỹ sau grant -> hold -> cancel/release. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class PromoBudgetPostgresTests {
    private val schema = "promo_budget_test_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var admin: JdbcTemplate
    private lateinit var jdbc: JdbcTemplate
    private lateinit var service: PromoBudgetService

    @BeforeEach fun setup() {
        val url = System.getenv("TEST_DB_URL")
        val user = System.getenv("TEST_DB_USER")
        val password = System.getenv("TEST_DB_PASSWORD")
        admin = JdbcTemplate(DriverManagerDataSource(url, user, password))
        admin.execute("CREATE SCHEMA $schema")
        val dataSource = DriverManagerDataSource(url, user, password).apply {
            connectionProperties = Properties().apply { setProperty("currentSchema", this@PromoBudgetPostgresTests.schema) }
        }
        jdbc = JdbcTemplate(dataSource)
        Files.list(Path.of("src/main/resources/db/migration")).use { files ->
            files.filter { it.fileName.toString().endsWith(".sql") }
                .sorted(compareBy { it.fileName.toString().substringBefore("__").drop(1).toInt() })
                .forEach { jdbc.execute(Files.readString(it).removePrefix("\uFEFF")) }
        }
        jdbc.update("INSERT INTO customers(id,firebase_uid,status,wallet_balance) VALUES(7003,'promo-budget-customer','ACTIVE',0)")
        jdbc.update("UPDATE promo_budget SET budget_limit=100000, held_amount=0, spent_amount=0, issuance_reserved=20000 WHERE id=1")
        service = PromoBudgetService(jdbc)
    }

    @AfterEach fun cleanup() {
        if (::admin.isInitialized) admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
    }

    private fun voucher(forecast: Long) {
        jdbc.update(
            """INSERT INTO vouchers(id,code,discount_type,value,minimum_order_amount,valid_from,valid_to,
                active,usage_limit,usage_count,source,owner_customer_id,forecast_reserve_per_use)
                VALUES(2,NULL,'FIXED',10000,0,CURRENT_TIMESTAMP - interval '1 day',
                CURRENT_TIMESTAMP + interval '1 day',TRUE,1,0,'SYSTEM_REWARD',7003,$forecast)""".trimIndent(),
        )
    }

    private fun assertBudget(held: Long, reserved: Long, spent: Long = 0L) {
        val summary = service.summary()
        assertEquals(100_000L, summary.budgetLimit)
        assertEquals(held, summary.heldAmount)
        assertEquals(reserved, summary.issuanceReserved)
        assertEquals(spent, summary.spentAmount)
        assertEquals(100_000L - held - reserved - spent, summary.availableAmount)
    }

    @Test fun `discount below forecast releases exactly the forecast back to reserve`() {
        voucher(20_000)
        service.hold(9101L, 2L, amount = 10_000L, forecast = 20_000L)
        assertBudget(held = 10_000L, reserved = 0L)

        service.release(9101L)
        assertBudget(held = 0L, reserved = 20_000L)
    }

    @Test fun `discount above forecast still releases only the forecast`() {
        voucher(20_000)
        service.hold(9102L, 2L, amount = 30_000L, forecast = 20_000L)
        assertBudget(held = 30_000L, reserved = 0L)

        service.release(9102L)
        assertBudget(held = 0L, reserved = 20_000L)
    }

    @Test fun `legacy voucher with zero forecast never consumes reserve`() {
        voucher(0)
        service.hold(9103L, 2L, amount = 10_000L, forecast = 0L)
        assertBudget(held = 10_000L, reserved = 20_000L)

        service.release(9103L)
        assertBudget(held = 0L, reserved = 20_000L)
    }

    @Test fun `expired personal voucher held then released returns reserve exactly once`() {
        voucher(20_000)
        service.hold(9110L, 2L, amount = 10_000L, forecast = 20_000L)
        assertBudget(held = 10_000L, reserved = 0L)

        // Voucher hết hạn trước khi ride bị huỷ: release() vẫn phải hoàn phần đã trích lúc HOLD.
        jdbc.update("UPDATE vouchers SET valid_to = CURRENT_TIMESTAMP - interval '1 second' WHERE id = 2")
        service.release(9110L)
        assertBudget(held = 0L, reserved = 20_000L)
        assertEquals(
            true,
            jdbc.queryForObject("SELECT reserve_released FROM vouchers WHERE id = 2", Boolean::class.java),
        )

        // summary() chạy V47 sweeper: không được cộng lần hai dù ledger còn dòng HOLD.
        assertBudget(held = 0L, reserved = 20_000L)
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM promo_budget_ledger WHERE voucher_id = 2 AND action = 'RELEASE'",
                Int::class.java,
            ),
        )
    }
}
