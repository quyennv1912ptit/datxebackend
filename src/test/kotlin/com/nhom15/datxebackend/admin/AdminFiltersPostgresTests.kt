package com.nhom15.datxebackend.admin

import com.nhom15.datxebackend.admin.dto.*
import com.nhom15.datxebackend.admin.repository.*
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.driver.dto.AdminDriverFilter
import com.nhom15.datxebackend.payment.entity.WithdrawalStatus
import com.nhom15.datxebackend.payment.repository.WithdrawalRepository
import com.nhom15.datxebackend.ride.dto.request.RideListFilter
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.support.SupportService
import com.nhom15.datxebackend.support.ticket.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito.mock
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.*
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Properties
import java.util.UUID

/** Disposable schema only; no application data is read or changed. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminFiltersPostgresTests {
    private val schema = "admin_filters_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var admin: JdbcTemplate
    private lateinit var jdbc: JdbcTemplate
    private lateinit var factory: LocalContainerEntityManagerFactoryBean
    private lateinit var repos: JpaRepositoryFactory
    private val from = Instant.parse("2026-11-07T17:00:00Z")
    private val to = Instant.parse("2026-11-08T17:00:00Z")

    @BeforeAll fun setup() {
        val url = System.getenv("TEST_DB_URL")
        val user = System.getenv("TEST_DB_USER")
        val password = System.getenv("TEST_DB_PASSWORD")
        admin = JdbcTemplate(DriverManagerDataSource(url, user, password))
        admin.execute("CREATE SCHEMA $schema")
        val ds = DriverManagerDataSource(url, user, password).apply {
            connectionProperties = Properties().apply { setProperty("currentSchema", this@AdminFiltersPostgresTests.schema) }
        }
        jdbc = JdbcTemplate(ds)
        Files.list(Path.of("src/main/resources/db/migration")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".sql") }
                .sorted(compareBy { it.fileName.toString().substringBefore("__").drop(1).toInt() })
                .forEach { jdbc.execute(Files.readString(it)) }
        }
        factory = LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(ds)
            setPackagesToScan("com.nhom15.datxebackend")
            jpaVendorAdapter = HibernateJpaVendorAdapter()
            setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none", "hibernate.jdbc.time_zone" to "UTC"))
            afterPropertiesSet()
        }
        repos = JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory.`object`!!))
        jdbc.update("INSERT INTO customers(id,firebase_uid,full_name,phone_number,status) VALUES(1,'filter-c1','Khách Bình','0901111111','ACTIVE'),(2,'filter-c2','Literal%_','0902222222','ACTIVE')")
        jdbc.update("INSERT INTO drivers(id,firebase_uid,full_name,phone_number,status,bank_name,bank_account_number) VALUES(1,'filter-d1','Tài Xế An','0912345678','ONLINE','Vietcombank','001234'),(2,'filter-d2','Literal%_','0987654321','BLOCKED','ACB','009999')")
        jdbc.update("INSERT INTO vehicles(id,customer_id,plate_number,vehicle_type,transmission_type) VALUES(1,1,'FILTER-001','CAR','AUTO'),(2,2,'FILTER-002','CAR','AUTO')")
        for ((id, created, driver) in listOf(Triple(11,from,1),Triple(12,from.plusSeconds(3600),1),Triple(13,to,null),Triple(14,from.minusSeconds(1),2))) {
            jdbc.update("""INSERT INTO rides(id,customer_id,driver_id,vehicle_id,status,type,vehicle_type,transmission,fare_estimate,payment_method,pickup_lat,pickup_lng,pickup_address,dropoff_lat,dropoff_lng,dropoff_address,created_at)
                VALUES(?,1,?,1,'COMPLETED','INSTANT','CAR','AUTO',100000,'CASH',21,105,'Đón',21,105,'Trả',?)""",id,driver,java.sql.Timestamp.from(created))
        }
        jdbc.update("INSERT INTO support_tickets(id,owner_id,owner_role,client_request_id,category,description,status,ride_id,created_at) VALUES(41,1,'CUSTOMER',?,'TRIP','Cần giúp','OPEN',11,?),(42,1,'DRIVER',?,'PAYMENT','Tiền','OPEN',12,?)",UUID.randomUUID(),java.sql.Timestamp.from(from),UUID.randomUUID(),java.sql.Timestamp.from(to))
        jdbc.update("INSERT INTO withdrawal_requests(id,driver_id,amount,status,idempotency_key,created_at) VALUES(51,1,50000,'PENDING','filter-1',?),(52,2,60000,'PENDING','filter-2',?)",java.sql.Timestamp.from(from),java.sql.Timestamp.from(to))
        jdbc.update("INSERT INTO wallet_transactions(id,customer_id,wallet_type,amount,type,transaction_type,status,description,created_at) VALUES(61,1,'CUSTOMER',50000,'DEPOSIT','TOP_UP','SUCCESS','Nạp 100%_!',?),(62,1,'CUSTOMER',50000,'DEPOSIT','TOP_UP','SUCCESS','Nạp khác',?),(63,2,'CUSTOMER',50000,'DEPOSIT','TOP_UP','SUCCESS','Nạp 100%_!',?)",java.sql.Timestamp.from(from),java.sql.Timestamp.from(to),java.sql.Timestamp.from(from))
    }

    @AfterAll fun cleanup() {
        if (::factory.isInitialized) factory.destroy()
        if (::admin.isInitialized) admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
    }

    @Test fun `rides combine names phone multi status dates and correct pagination`() {
        val repository = repos.getRepository(RideRepository::class.java)
        val filter = RideListFilter(q="khách bình",createdFrom=from,createdTo=to)
        val spec = AdminRideSpecifications.matching(filter,listOf(RideStatus.COMPLETED,RideStatus.IN_PROGRESS))
        val first = repository.findAll(spec,PageRequest.of(0,1,Sort.by(Sort.Direction.DESC,"createdAt","id")))
        assertEquals(2L,first.totalElements)
        assertEquals(12L,first.content.single().id)
        assertTrue(first.hasNext())
        assertEquals(11L,repository.findAll(spec,PageRequest.of(1,1,Sort.by(Sort.Direction.DESC,"createdAt","id"))).content.single().id)
        assertEquals(2,repository.findAll(AdminRideSpecifications.matching(filter.copy(q="0912345678"),null)).size)
        assertEquals(1,repository.findAll(AdminRideSpecifications.matching(RideListFilter(q="#13"),null)).size)
        assertTrue(repository.findAll(AdminRideSpecifications.matching(filter,listOf(RideStatus.CANCELLED))).isEmpty())
    }

    @Test fun `withdrawal search includes bank fields applies dates before count and handles null filters`() {
        val repository = repos.getRepository(WithdrawalRepository::class.java)
        val filter = AdminSearchFilter(q="vietCOMbank",createdFrom=from,createdTo=to)
        val result = repository.findAll(AdminWithdrawalSpecifications.matching(WithdrawalStatus.PENDING,filter),PageRequest.of(0,1))
        assertEquals(1L,result.totalElements)
        assertEquals(51L,result.content.single().id)
        assertEquals(1,repository.findAll(AdminWithdrawalSpecifications.matching(null,AdminSearchFilter(q="009999"))).size)
        assertEquals(1,repository.findAll(AdminWithdrawalSpecifications.matching(null,AdminSearchFilter(q="#52"))).size)
    }

    @Test fun `ticket count and content use identical search role category and date conditions`() {
        val service = TicketService(mock(SupportService::class.java),mock(SupportTicketRepository::class.java),mock(TicketMessageRepository::class.java),jdbc,mock(ApplicationEventPublisher::class.java))
        val result = service.adminList(TicketStatus.OPEN,0,1,AdminSearchFilter(q="khách bình",createdFrom=from,createdTo=to),"CUSTOMER","TRIP")
        assertEquals(1L,result.totalElements)
        assertEquals(41L,result.content.single().id)
        assertEquals(1L,service.adminList(null,0,20,AdminSearchFilter(q="HT-42")).totalElements)
        assertEquals(0L,service.adminList(null,0,20,AdminSearchFilter(q="khách bình"),"DRIVER").totalElements)
    }

    @Test fun `wallet filters scope customer and escape literal search metacharacters`() {
        val repository = AdminCustomerQueryRepository(jdbc)
        val search = AdminSearchFilter(q="100%_!",createdFrom=from,createdTo=to)
        val filter = AdminWalletFilter(type="DEPOSIT",status="SUCCESS")
        assertEquals(1,repository.countWalletTransactions(1,search,filter))
        assertEquals(61L,repository.findWalletTransactions(1,0,20,search,filter).single().id)
        assertEquals(0,repository.countWalletTransactions(1,search,filter.copy(status="FAILED")))
        assertEquals(1,repository.countWalletTransactions(1,AdminSearchFilter(q="#61")))
    }

    @Test fun `customer and driver searches match literal wildcards and blocked is not online`() {
        assertEquals(1L,AdminCustomerQueryRepository(jdbc).count(AdminCustomerFilter(q="%_")))
        val repository = AdminDriverQueryRepository(jdbc)
        assertEquals(1L,repository.count(AdminDriverFilter(search="%_"),null))
        assertEquals(1L,repository.count(AdminDriverFilter(online=true),null))
    }

    @Test fun `invalid dates long queries and invalid transaction types are rejected`() {
        assertThrows(BadRequestException::class.java) { AdminSearchFilter(createdFrom=to,createdTo=from).validate() }
        assertThrows(BadRequestException::class.java) { AdminSearchFilter(createdFrom=from,createdTo=from).validate() }
        assertThrows(BadRequestException::class.java) { AdminSearchFilter(q="x".repeat(201)).validate() }
        assertThrows(BadRequestException::class.java) { AdminWalletFilter(type="arbitrary").validate() }
    }
}
