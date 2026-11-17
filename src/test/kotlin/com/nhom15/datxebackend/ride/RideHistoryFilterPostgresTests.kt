package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.chat.service.ChatService
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.service.PaymentService
import com.nhom15.datxebackend.ride.dto.request.RideListFilter
import com.nhom15.datxebackend.ride.repository.HandoverPhotoRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.repository.RideStatusHistoryRepository
import com.nhom15.datxebackend.ride.repository.VehicleHandoverReportRepository
import com.nhom15.datxebackend.ride.service.RideQueryService
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.Properties
import java.util.UUID

/** Disposable schema only; no application data is read or changed. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RideHistoryFilterPostgresTests {
    private val schema = "ride_history_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var admin: JdbcTemplate
    private lateinit var jdbc: JdbcTemplate
    private lateinit var factory: LocalContainerEntityManagerFactoryBean
    private lateinit var service: RideQueryService
    private val from = Instant.parse("2026-11-10T17:00:00Z")
    private val to = Instant.parse("2026-11-17T17:00:00Z")

    @BeforeAll
    fun setup() {
        val url = System.getenv("TEST_DB_URL")
        val user = System.getenv("TEST_DB_USER")
        val password = System.getenv("TEST_DB_PASSWORD")
        admin = JdbcTemplate(DriverManagerDataSource(url, user, password))
        admin.execute("CREATE SCHEMA $schema")
        val ds = DriverManagerDataSource(url, user, password).apply {
            connectionProperties = Properties().apply { setProperty("currentSchema", this@RideHistoryFilterPostgresTests.schema) }
        }
        jdbc = JdbcTemplate(ds)
        Files.list(Path.of("src/main/resources/db/migration")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".sql") }
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
        val repos = JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory.`object`!!))
        service = RideQueryService(
            repos.getRepository(RideRepository::class.java),
            repos.getRepository(RideStatusHistoryRepository::class.java),
            repos.getRepository(VehicleHandoverReportRepository::class.java),
            repos.getRepository(HandoverPhotoRepository::class.java),
            Mockito.mock(ChatService::class.java),
            repos.getRepository(CustomerRepository::class.java),
            repos.getRepository(DriverRepository::class.java),
            Mockito.mock(PaymentService::class.java),
        )

        jdbc.update("INSERT INTO customers(id,firebase_uid,full_name,phone_number,status) VALUES(1,'rh-c1','Khách 1','0901000001','ACTIVE'),(2,'rh-c2','Khách 2','0901000002','ACTIVE')")
        jdbc.update("INSERT INTO drivers(id,firebase_uid,full_name,phone_number,status) VALUES(1,'rh-d1','Tài Xế 1','0902000001','ONLINE'),(2,'rh-d2','Tài Xế 2','0902000002','ONLINE')")
        jdbc.update("INSERT INTO vehicles(id,customer_id,plate_number,vehicle_type,transmission_type) VALUES(1,1,'30A-11111','CAR','AUTO'),(2,2,'29B-22222','CAR','AUTO')")

        val rows = listOf(
            // Driver 1, Customer 1, inside [from, to)
            arrayOf(101L, 1L, 1L, "COMPLETED", "29A-10001", "18 Nguyễn Chí Thanh", "144 Xuân Thủy, Cầu Giấy", Timestamp.from(from)),
            arrayOf(102L, 1L, 1L, "COMPLETED", "30A-99999", "20 Láng Hạ", "88 Cầu Giấy", Timestamp.from(from.plusSeconds(3600))),
            arrayOf(103L, 1L, 1L, "COMPLETED", "30A-99999", "10 Kim Mã", "89 Cầu Giấy", Timestamp.from(from.plusSeconds(7200))),
            arrayOf(104L, 1L, 1L, "CANCELLED", "30A-99999", "55 Cầu Giấy", "Sân bay Nội Bài", Timestamp.from(from.plusSeconds(10800))),
            // Boundary cases for Driver 1: before `from`, and exact `to` (half-open excludes `to`)
            arrayOf(105L, 1L, 1L, "COMPLETED", "30A-99999", "Cầu Giấy", "Hồ Gươm", Timestamp.from(from.minusSeconds(1))),
            arrayOf(106L, 1L, 1L, "COMPLETED", "30A-99999", "Cầu Giấy", "Hồ Tây", Timestamp.from(to)),
            // Different driver & customer isolation + LIKE wildcard literal test
            arrayOf(107L, 2L, 2L, "COMPLETED", "51F-100%_", "100%_ Lê Lợi", "Quận 1", Timestamp.from(from.plusSeconds(1800))),
        )
        rows.forEach { row ->
            jdbc.update(
                """
                INSERT INTO rides(id,customer_id,driver_id,vehicle_id,status,type,vehicle_type,transmission,license_plate,fare_estimate,payment_method,pickup_lat,pickup_lng,pickup_address,dropoff_lat,dropoff_lng,dropoff_address,created_at)
                VALUES(?,?,?,?,?,'INSTANT','CAR','AUTO',?,100000,'CASH',21,105,?,21,105,?,?)
                """.trimIndent(),
                row[0], row[1], row[2], row[1], row[3], row[4], row[5], row[6], row[7],
            )
        }
    }

    @AfterAll
    fun cleanup() {
        if (::factory.isInitialized) factory.destroy()
        if (::admin.isInitialized) admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
    }

    @Test
    fun `filters before pagination across multi-page driver history and respects half-open UTC boundaries`() {
        val driver1 = AuthUser(1L, "rh-d1", Role.DRIVER, "Tài Xế 1", null, null, "ONLINE")
        val page0 = service.listMyRides(
            driver1,
            RideListFilter(status = "COMPLETED", page = 0, size = 1, q = "cầu giấy", createdFrom = from, createdTo = to),
        )
        assertEquals(3L, page0.totalElements)
        assertEquals(3, page0.totalPages)
        assertTrue(page0.hasNext)
        assertEquals(listOf(103L), page0.content.map { it.id })

        val page1 = service.listMyRides(
            driver1,
            RideListFilter(status = "COMPLETED", page = 1, size = 1, q = "cầu giấy", createdFrom = from, createdTo = to),
        )
        assertEquals(3L, page1.totalElements)
        assertTrue(page1.hasNext)
        assertEquals(listOf(102L), page1.content.map { it.id })

        val page2 = service.listMyRides(
            driver1,
            RideListFilter(status = "COMPLETED", page = 2, size = 1, q = "cầu giấy", createdFrom = from, createdTo = to),
        )
        assertFalse(page2.hasNext)
        assertEquals(listOf(101L), page2.content.map { it.id })
    }

    @Test
    fun `enforces role isolation, id and plate search, and date range validation`() {
        val driver1 = AuthUser(1L, "rh-d1", Role.DRIVER, "Tài Xế 1", null, null, "ONLINE")
        val customer2 = AuthUser(2L, "rh-c2", Role.CUSTOMER, "Khách 2", null, null, "ACTIVE")
        val adminUser = AuthUser(99L, "adm", Role.ADMIN, "Admin", null, null, "ACTIVE")

        // Driver 1 cannot see Ride 107 owned by Driver 2
        assertEquals(0L, service.listMyRides(driver1, RideListFilter(q = "#107")).totalElements)
        // Plate search finds ride 101 across full history
        assertEquals(listOf(101L), service.listMyRides(driver1, RideListFilter(q = "29A-10001")).content.map { it.id })
        // Literal %_ escaping only matches ride 107 for Customer 2
        assertEquals(listOf(107L), service.listMyRides(customer2, RideListFilter(q = "100%_")).content.map { it.id })
        assertEquals(RideStatus.CANCELLED, service.listMyRides(driver1, RideListFilter(status = "CANCELLED")).content.single().status)

        assertThrows(ForbiddenException::class.java) { service.listMyRides(adminUser, RideListFilter()) }
        assertThrows(BadRequestException::class.java) { service.listMyRides(driver1, RideListFilter(createdFrom = to, createdTo = from)) }
    }
}
