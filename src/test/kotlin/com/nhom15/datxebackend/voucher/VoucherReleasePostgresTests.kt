package com.nhom15.datxebackend.voucher

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.voucher.service.VoucherService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

/** PostgreSQL thật: xác nhận hoàn usage_count và cho khách dùng lại voucher sau khi chuyến không hoàn thành. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class VoucherReleasePostgresTests {
    private val schema = "voucher_test_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var admin: JdbcTemplate
    private lateinit var jdbc: JdbcTemplate
    private lateinit var vouchers: VoucherService

    @BeforeEach fun setup() {
        val url = System.getenv("TEST_DB_URL")
        val user = System.getenv("TEST_DB_USER")
        val password = System.getenv("TEST_DB_PASSWORD")
        admin = JdbcTemplate(DriverManagerDataSource(url, user, password))
        admin.execute("CREATE SCHEMA $schema")
        val dataSource = DriverManagerDataSource(url, user, password).apply {
            connectionProperties = Properties().apply { setProperty("currentSchema", this@VoucherReleasePostgresTests.schema) }
        }
        jdbc = JdbcTemplate(dataSource)
        Files.list(Path.of("src/main/resources/db/migration")).use { files ->
            files.filter { it.fileName.toString().endsWith(".sql") }
                .sorted(compareBy { it.fileName.toString().substringBefore("__").drop(1).toInt() })
                .forEach { jdbc.execute(Files.readString(it)) }
        }
        vouchers = VoucherService(jdbc)
        jdbc.update("INSERT INTO customers(id,firebase_uid,status,wallet_balance) VALUES(7002,'voucher-customer','ACTIVE',0)")
        jdbc.update("INSERT INTO vehicles(id,customer_id,plate_number,vehicle_type,transmission_type) VALUES(8001,7002,'TEST-001','CAR','AUTO')")
        jdbc.update("""INSERT INTO rides(id,customer_id,vehicle_id,status,type,vehicle_type,transmission,fare_estimate,
            payment_method,pickup_lat,pickup_lng,pickup_address,dropoff_lat,dropoff_lng,dropoff_address)
            VALUES(9001,7002,8001,'CANCELLED','INSTANT','CAR','AUTO',100000,'CASH',21.03,105.84,'Đón',21.04,105.85,'Trả')""".trimIndent())
        jdbc.update("""INSERT INTO vouchers(id,code,discount_type,value,minimum_order_amount,valid_from,valid_to,active,usage_limit,usage_count)
            VALUES(1,'GIAM10','FIXED',10000,0,CURRENT_TIMESTAMP - interval '1 day', CURRENT_TIMESTAMP + interval '1 day', TRUE, 100, 1)""".trimIndent())
        jdbc.update("INSERT INTO voucher_usages(voucher_id,customer_id,ride_id) VALUES(1,7002,9001)")
    }

    @AfterEach fun cleanup() {
        if (::admin.isInitialized) admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
    }

    private val customerActor = AuthUser(7002, "customer", Role.CUSTOMER, "Khách", null, null, "ACTIVE")

    @Test fun `cancelled ride returns the voucher and repeated release is idempotent`() {
        assertTrue(vouchers.listAvailable(customerActor).isEmpty())

        vouchers.releaseForRide(9001)

        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM voucher_usages WHERE ride_id=9001", Long::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT usage_count FROM vouchers WHERE id=1", Int::class.java))
        assertEquals(listOf("GIAM10"), vouchers.listAvailable(customerActor).map { it.code })

        vouchers.releaseForRide(9001)

        assertEquals(0, jdbc.queryForObject("SELECT usage_count FROM vouchers WHERE id=1", Int::class.java))
        assertEquals(1, vouchers.listAvailable(customerActor).size)
    }

    @Test fun `released voucher can be consumed by a new ride`() {
        vouchers.releaseForRide(9001)
        val applied = vouchers.calculate(customerActor, "GIAM10", 100_000)!!

        vouchers.consume(7002, applied, 9001)

        assertEquals(1, jdbc.queryForObject("SELECT usage_count FROM vouchers WHERE id=1", Int::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM voucher_usages WHERE ride_id=9001", Long::class.java))
    }
}
