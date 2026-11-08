package com.nhom15.datxebackend.support

import com.cloudinary.Cloudinary
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.*
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** All migrations and writes run inside a unique transactional schema and are rolled back. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class SupportPersistenceTests {
    private lateinit var connection: Connection
    private lateinit var jdbc: JdbcTemplate
    private lateinit var service: SupportService
    private val customer = AuthUser(11, "customer", Role.CUSTOMER, "Khách", null, null, "ACTIVE")
    private val driver = customer.copy(role = Role.DRIVER)
    private val admin = customer.copy(id = 3, role = Role.ADMIN)

    @BeforeEach fun setup() {
        connection = DriverManager.getConnection(System.getenv("TEST_DB_URL"), System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"))
        connection.autoCommit = false
        jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val schema = "support_test_" + UUID.randomUUID().toString().replace("-", "")
        jdbc.execute("CREATE SCHEMA $schema")
        jdbc.execute("SET LOCAL search_path TO $schema")
        Files.list(Path.of("src/main/resources/db/migration")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".sql") }.sorted(compareBy { it.fileName.toString().substringBefore("__").drop(1).toInt() }).forEach { jdbc.execute(Files.readString(it)) }
        }
        service = SupportService(jdbc, Mockito.mock(RideRepository::class.java), CloudinaryAssetService(Cloudinary(mapOf("cloud_name" to "test-cloud"))))
    }
    @AfterEach fun cleanup() { if (::connection.isInitialized) { connection.rollback(); connection.close() } }

    @Test fun `lost-response retries have one persistent receipt and owner role is isolated`() {
        val request = CreateSupportTicket(UUID.randomUUID(), category = "ACCOUNT", description = "Không cập nhật được hồ sơ")
        val first = service.create(customer, request)
        val retry = service.create(customer, request)
        assertEquals(first.id, retry.id)
        assertEquals("HT-${first.id}", retry.code)
        assertEquals(1, service.list(customer, 0).size)
        assertTrue(service.list(driver, 0).isEmpty())
        assertThrows(NotFoundException::class.java) { service.detail(driver, first.id) }
        assertThrows(NotFoundException::class.java) { service.detail(customer.copy(id = 99), first.id) }
        val reopenedService = SupportService(jdbc, Mockito.mock(RideRepository::class.java), CloudinaryAssetService(Cloudinary(mapOf("cloud_name" to "test-cloud"))))
        assertEquals(first.id, reopenedService.list(customer, 0).single().id)
    }

    @Test fun `official admin queue can respond and customer reads persistent resolution`() {
        val ticket = service.create(driver, CreateSupportTicket(UUID.randomUUID(), category = "OTHER", description = "Tôi cần hỗ trợ về tài khoản"))
        assertEquals(ticket.id, service.list(admin, 0, "OPEN").single().id)
        assertThrows(ForbiddenException::class.java) { service.update(driver, ticket.id, UpdateSupportTicket("RESOLVED", "Tự đóng")) }
        assertThrows(BadRequestException::class.java) { service.update(admin, ticket.id, UpdateSupportTicket("RESOLVED", " ")) }
        service.update(admin, ticket.id, UpdateSupportTicket("IN_PROGRESS", "Đang kiểm tra"))
        service.update(admin, ticket.id, UpdateSupportTicket("RESOLVED", "Đã xử lý yêu cầu của bạn"))
        val refreshed = service.detail(driver, ticket.id)
        assertEquals("RESOLVED", refreshed.status)
        assertEquals("Đã xử lý yêu cầu của bạn", refreshed.response)
        assertTrue(service.list(admin, 0, "OPEN").isEmpty())
    }

    @Test fun `support image belongs to sender and is retained with receipt`() {
        val publicId = "app_dat_xe/customer/11/real-photo"
        val image = SupportImage("https://res.cloudinary.com/test-cloud/image/upload/v1/$publicId.jpg", publicId)
        val request = CreateSupportTicket(UUID.randomUUID(), category = "OTHER", description = "Ảnh sự cố cần kiểm tra", images = listOf(image))
        assertEquals(listOf(image), service.create(customer, request).images)
        assertEquals(listOf(image), service.create(customer, request).images)
        assertThrows(BadRequestException::class.java) { service.create(driver, request.copy(clientRequestId = UUID.randomUUID())) }
    }

    @Test fun `member filters cover older pages and isolate customer from driver with same id`() {
        val own = service.create(customer, CreateSupportTicket(UUID.randomUUID(), category = "PAYMENT", description = "Kiểm tra khoản thanh toán của tôi"))
        service.update(admin, own.id, UpdateSupportTicket("RESOLVED", "Đã kiểm tra xong"))
        repeat(22) { service.create(customer, CreateSupportTicket(UUID.randomUUID(), category = "OTHER", description = "Yêu cầu mới thứ $it cần hỗ trợ")) }
        service.create(driver, CreateSupportTicket(UUID.randomUUID(), category = "OTHER", description = "Tài xế cần được kiểm tra hỗ trợ"))
        assertFalse(service.list(customer, 0).any { it.id == own.id })
        assertEquals(own.id, service.list(customer, 0, "RESOLVED").single().id)
        assertTrue(service.list(driver, 0, "RESOLVED").isEmpty())
        assertEquals(20, service.list(customer, 0, "OPEN").size)
        assertEquals(2, service.list(customer, 1, "OPEN").size)
        assertThrows(BadRequestException::class.java) { service.list(customer, 0, "INVALID") }
    }

    @Test fun `both apps see the same admin processing states response and evidence`() {
        for (owner in listOf(customer, driver)) {
            val publicId = "app_dat_xe/${owner.role.name.lowercase()}/${owner.id}/support-evidence"
            val image = SupportImage("https://res.cloudinary.com/test-cloud/image/upload/v1/$publicId.jpg", publicId)
            val ticket = service.create(owner, CreateSupportTicket(UUID.randomUUID(), category = "HANDOVER", description = "Biên bản bàn giao cần kiểm tra lại", images = listOf(image)))
            val progress = service.update(admin, ticket.id, UpdateSupportTicket("IN_PROGRESS", "Đang đối chiếu biên bản", ticket.updatedAt))
            assertEquals(progress, service.list(owner, 0, "IN_PROGRESS").single())
            val resolved = service.update(admin, ticket.id, UpdateSupportTicket("RESOLVED", "Đã hoàn tất đối chiếu", progress.updatedAt))
            assertEquals(resolved, service.detail(owner, ticket.id))
            assertEquals(listOf(image), resolved.images)
            assertTrue(resolved.updatedAt > progress.updatedAt)
            assertTrue(service.list(owner, 0, "IN_PROGRESS").isEmpty())
        }
    }

    @Test fun `stale admin cannot overwrite another admin response`() {
        val ticket = service.create(customer, CreateSupportTicket(UUID.randomUUID(), category = "OTHER", description = "Hỗ trợ kiểm tra thông tin cá nhân"))
        val first = service.update(admin, ticket.id, UpdateSupportTicket("IN_PROGRESS", "Admin thứ nhất đang xử lý", ticket.updatedAt))
        assertThrows(ConflictException::class.java) {
            service.update(admin.copy(id = 4), ticket.id, UpdateSupportTicket("RESOLVED", "Phản hồi từ bản cũ", ticket.updatedAt))
        }
        assertEquals(first, service.detail(customer, ticket.id))
        assertEquals(3L, jdbc.queryForObject("SELECT handled_by FROM support_tickets WHERE id=?", Long::class.java, ticket.id))
    }

    @Test fun `reused receipt key with different content fails instead of silently returning old complaint`() {
        val request = CreateSupportTicket(UUID.randomUUID(), category = "ACCOUNT", description = "Kiểm tra tài khoản cần hỗ trợ")
        val ticket = service.create(customer, request)
        assertThrows(ConflictException::class.java) { service.create(customer, request.copy(description = "Nội dung khiếu nại hoàn toàn khác")) }
        assertEquals(ticket, service.detail(customer, ticket.id))
        assertEquals(1, service.list(customer, 0).size)
    }

    @Test fun `cash receipt survives a fresh service and repeated confirmation preserves paidAt`() {
        jdbc.update("INSERT INTO customers(id,firebase_uid) VALUES (11,'test-customer')")
        jdbc.update("INSERT INTO drivers(id,firebase_uid,phone_number) VALUES (11,'test-driver','0900000000')")
        jdbc.update("INSERT INTO vehicles(id,customer_id,plate_number) VALUES (2,11,'TEST-01')")
        jdbc.update("INSERT INTO rides(id,customer_id,driver_id,vehicle_id,type,status,fare_estimate,payment_method,transmission,vehicle_type) VALUES (12,11,11,2,'INSTANT','COMPLETED',30000,'CASH','AUTO','CAR')")
        jdbc.update("INSERT INTO payments(ride_id,customer_id,driver_id,amount,method,status) VALUES (12,11,11,30000,'CASH','PENDING')")
        val rides = Mockito.mock(RideRepository::class.java)
        val ride = com.nhom15.datxebackend.ride.entity.Ride(customerId = 11, driverId = 11, vehicleId = 2,
            type = com.nhom15.datxebackend.ride.entity.RideType.INSTANT, pickup = com.nhom15.datxebackend.common.domain.Location(), dropoff = com.nhom15.datxebackend.common.domain.Location(),
            vehicleType = com.nhom15.datxebackend.common.enums.VehicleType.CAR, transmission = com.nhom15.datxebackend.common.enums.Transmission.AUTO, fareEstimate = 30000, paymentMethod = com.nhom15.datxebackend.common.enums.PaymentMethod.CASH).apply { id = 12; status = com.nhom15.datxebackend.common.enums.RideStatus.COMPLETED }
        Mockito.`when`(rides.findByIdForUpdate(12)).thenReturn(java.util.Optional.of(ride))
        val drivers = Mockito.mock(com.nhom15.datxebackend.driver.repository.DriverRepository::class.java)
        val payments = com.nhom15.datxebackend.payment.service.PaymentService(jdbc, drivers, rides, Mockito.mock(com.nhom15.datxebackend.payment.service.WalletService::class.java), Mockito.mock(org.springframework.context.ApplicationEventPublisher::class.java))
        val paid = payments.confirmCashReceived(driver, 12)
        assertEquals("PAID", paid.status)
        assertNotNull(paid.paidAt)
        jdbc.update("UPDATE payments SET paid_at='2026-11-01T01:02:03Z' WHERE ride_id=12")
        val retried = payments.confirmCashReceived(driver, 12)
        assertEquals(java.time.Instant.parse("2026-11-01T01:02:03Z"), retried.paidAt)
        val freshService = com.nhom15.datxebackend.payment.service.PaymentService(jdbc, drivers, rides, Mockito.mock(com.nhom15.datxebackend.payment.service.WalletService::class.java), Mockito.mock(org.springframework.context.ApplicationEventPublisher::class.java))
        assertEquals("PAID", freshService.getRidePayment(12)?.status)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM payments WHERE ride_id=12", Int::class.java))
        assertThrows(ForbiddenException::class.java) { payments.confirmCashReceived(customer, 12) }
    }

    @Test fun `image-only message is allowed and empty text is rejected by the database`() {
        val ticket = service.create(customer, CreateSupportTicket(UUID.randomUUID(), category = "OTHER", description = "Cần gửi ảnh minh chứng"))
        jdbc.update(
            "INSERT INTO support_ticket_messages(ticket_id,sender_id,sender_role,body,image_url) VALUES (?,?,?,?,?)",
            ticket.id, customer.id, "CUSTOMER", "", "https://res.cloudinary.com/demo/image/upload/v1/app_dat_xe/customer/11/photo.jpg",
        )
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM support_ticket_messages WHERE ticket_id=? AND image_url IS NOT NULL",
            Int::class.java, ticket.id,
        ))
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "INSERT INTO support_ticket_messages(ticket_id,sender_id,sender_role,body) VALUES (?,?,?,?)",
                ticket.id, customer.id, "CUSTOMER", "",
            )
        }
    }
}
