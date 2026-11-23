package com.nhom15.datxebackend.support

import com.cloudinary.Cloudinary
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

/** M14: endpoint admin legacy retry với cùng response không tạo thêm tin nhắn ADMIN. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class LegacyAdminMessageDedupePostgresTests {
    private lateinit var connection: Connection
    private lateinit var jdbc: JdbcTemplate
    private lateinit var service: SupportService
    private val customer = AuthUser(11, "customer", Role.CUSTOMER, "Khách", null, null, "ACTIVE")
    private val admin = customer.copy(id = 3, role = Role.ADMIN)

    @BeforeEach fun setup() {
        connection = DriverManager.getConnection(System.getenv("TEST_DB_URL"), System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"))
        connection.autoCommit = false
        jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val schema = "ticket_dedupe_" + UUID.randomUUID().toString().replace("-", "")
        jdbc.execute("CREATE SCHEMA $schema")
        jdbc.execute("SET LOCAL search_path TO $schema")
        Files.list(Path.of("src/main/resources/db/migration")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".sql") }
                .sorted(compareBy { it.fileName.toString().substringBefore("__").drop(1).toInt() })
                .forEach { jdbc.execute(Files.readString(it).removePrefix("\uFEFF")) }
        }
        service = SupportService(jdbc, Mockito.mock(RideRepository::class.java), CloudinaryAssetService(Cloudinary(mapOf("cloud_name" to "test-cloud"))))
    }

    @AfterEach fun cleanup() {
        if (::connection.isInitialized) {
            connection.rollback()
            connection.close()
        }
    }

    @Test fun `legacy retry with same response inserts one admin message`() {
        val ticket = service.create(customer, CreateSupportTicket(UUID.randomUUID(), category = "OTHER", description = "Cần hỗ trợ đơn hàng"))
        val request = UpdateSupportTicket("IN_PROGRESS", "Admin đang kiểm tra")
        service.update(admin, ticket.id, request)
        service.update(admin, ticket.id, request)

        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM support_ticket_messages WHERE ticket_id=? AND sender_role='ADMIN' AND body=?",
            Long::class.java, ticket.id, "Admin đang kiểm tra",
        )
        assertEquals(1L, count)
    }

    @Test fun `V43 enforces one message per ticket and clientRequestId`() {
        val ticket = service.create(customer, CreateSupportTicket(UUID.randomUUID(), category = "OTHER", description = "Cần hỗ trợ thanh toán"))
        val key = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO support_ticket_messages(ticket_id,sender_id,sender_role,body,client_request_id) VALUES (?,?,?,?,?)",
            ticket.id, admin.id, "ADMIN", "Phản hồi", key,
        )
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "INSERT INTO support_ticket_messages(ticket_id,sender_id,sender_role,body,client_request_id) VALUES (?,?,?,?,?)",
                ticket.id, admin.id, "ADMIN", "Phản hồi bị lặp", key,
            )
        }
    }
}
