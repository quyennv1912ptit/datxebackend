package com.nhom15.datxebackend.support.ticket

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.support.SupportService
import com.nhom15.datxebackend.support.SupportTicket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/** M13/M15: retry không tạo row timeline trùng, URL ảnh phải khớp chính xác asset của người gửi. */
class TicketMessageIdempotencyTests {
    private var insertRows = 1
    private var storedMessage: TicketMessageEntity? = null
    private val jdbcCalls = mutableListOf<InvocationOnMock>()
    private val published = mutableListOf<Any>()

    private val support = mock(SupportService::class.java)
    private val tickets = mock(SupportTicketRepository::class.java)
    private val messages = mock(TicketMessageRepository::class.java, Answer { invocation ->
        when (invocation.method.name) {
            "findByTicketIdAndClientRequestId" -> storedMessage
            "findByTicketIdOrderByCreatedAtAscIdAsc" -> emptyList<TicketMessageEntity>()
            else -> null
        }
    })
    private val jdbc = mock(JdbcTemplate::class.java, Answer { invocation ->
        jdbcCalls += invocation
        if (invocation.isMessageInsert()) insertRows else 0
    })
    private val events = mock(ApplicationEventPublisher::class.java, Answer { invocation ->
        invocation.arguments.firstOrNull()?.let { published += it }
        null
    })
    private val service = TicketService(
        support, tickets, messages, jdbc, events, "cloudinary://key:secret@test-cloud",
    )
    private val customer = AuthUser(11, "customer", Role.CUSTOMER, "Khách", null, null, "ACTIVE")
    private val admin = customer.copy(id = 3, role = Role.ADMIN)
    private val key = UUID.randomUUID()

    private fun InvocationOnMock.isMessageInsert(): Boolean =
        method.name == "update" && (arguments.firstOrNull() as? String)?.contains("INSERT INTO support_ticket_messages") == true

    private fun messageInserts() = jdbcCalls.filter { it.isMessageInsert() }

    private fun legacyTicket() = SupportTicket(
        id = 42, code = "HT-42", rideId = null, category = "OTHER", description = "Cần hỗ trợ",
        status = "WAITING_FOR_USER", response = "old", images = emptyList(),
        createdAt = Instant.now(), updatedAt = Instant.now(), ownerId = 11, ownerRole = "CUSTOMER",
    )

    private fun stored(
        body: String,
        imageUrl: String? = null,
        senderId: Long = 11,
        role: TicketSenderRole = TicketSenderRole.CUSTOMER,
        requestedStatus: TicketStatus? = null,
    ) = TicketMessageEntity(42, senderId, role, body, imageUrl, key, requestedStatus).apply { id = 99 }

    private fun stub(insertedRows: Int, existing: TicketMessageEntity) {
        insertRows = insertedRows
        storedMessage = existing
    }

    @Test
    fun `retry with same clientRequestId returns original message and skips ticket side effects`() {
        val original = stored(
            "Đã xử lý", senderId = 3, role = TicketSenderRole.ADMIN,
            requestedStatus = TicketStatus.WAITING_FOR_USER,
        )
        stub(0, original)
        val ticket = SupportTicketEntity(
            ownerId = 11, ownerRole = Role.CUSTOMER, clientRequestId = UUID.randomUUID(),
            category = "OTHER", description = "Cần hỗ trợ", status = TicketStatus.WAITING_FOR_USER, id = 42,
        )
        org.mockito.Mockito.`when`(tickets.findForUpdate(42)).thenReturn(ticket)
        val request = SendTicketMessageRequest("Đã xử lý", TicketStatus.WAITING_FOR_USER, clientRequestId = key)

        val result = service.sendAdminMessage(admin, 42, request)

        assertEquals(99L, result.id)
        assertEquals(TicketSenderRole.ADMIN, result.senderRole)
        assertEquals(TicketStatus.WAITING_FOR_USER, ticket.status)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `retry with the same id but a different status is rejected instead of dropping the status change`() {
        val original = stored(
            "Đã xử lý", senderId = 3, role = TicketSenderRole.ADMIN,
            requestedStatus = TicketStatus.WAITING_FOR_USER,
        )
        stub(0, original)
        val ticket = SupportTicketEntity(
            ownerId = 11, ownerRole = Role.CUSTOMER, clientRequestId = UUID.randomUUID(),
            category = "OTHER", description = "Cần hỗ trợ", status = TicketStatus.WAITING_FOR_USER, id = 42,
        )
        org.mockito.Mockito.`when`(tickets.findForUpdate(42)).thenReturn(ticket)
        val request = SendTicketMessageRequest("Đã xử lý", TicketStatus.RESOLVED, clientRequestId = key)

        assertThrows(ConflictException::class.java) { service.sendAdminMessage(admin, 42, request) }

        assertEquals(TicketStatus.WAITING_FOR_USER, ticket.status)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `replay after a later message advanced the ticket still returns the original message`() {
        val original = stored(
            "Đã xử lý", senderId = 3, role = TicketSenderRole.ADMIN,
            requestedStatus = TicketStatus.WAITING_FOR_USER,
        )
        stub(0, original)
        val ticket = SupportTicketEntity(
            ownerId = 11, ownerRole = Role.CUSTOMER, clientRequestId = UUID.randomUUID(),
            category = "OTHER", description = "Cần hỗ trợ", status = TicketStatus.RESOLVED, id = 42,
        )
        org.mockito.Mockito.`when`(tickets.findForUpdate(42)).thenReturn(ticket)
        val request = SendTicketMessageRequest("Đã xử lý", TicketStatus.WAITING_FOR_USER, clientRequestId = key)

        val result = service.sendAdminMessage(admin, 42, request)

        assertEquals(99L, result.id)
        // The current status reflects a newer message; it must not turn this replay into a 409
        // or roll the ticket back to the older intent.
        assertEquals(TicketStatus.RESOLVED, ticket.status)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `legacy row without a recorded intent replays even after the ticket status changed`() {
        // Rows written before V44 have NULL requested_status; there is no intent to compare,
        // so keep the original permissive replay instead of inventing a conflict.
        stub(0, stored("Đã xử lý", senderId = 3, role = TicketSenderRole.ADMIN))
        val ticket = SupportTicketEntity(
            ownerId = 11, ownerRole = Role.CUSTOMER, clientRequestId = UUID.randomUUID(),
            category = "OTHER", description = "Cần hỗ trợ", status = TicketStatus.RESOLVED, id = 42,
        )
        org.mockito.Mockito.`when`(tickets.findForUpdate(42)).thenReturn(ticket)

        val result = service.sendAdminMessage(
            admin, 42, SendTicketMessageRequest("Đã xử lý", TicketStatus.WAITING_FOR_USER, clientRequestId = key),
        )

        assertEquals(99L, result.id)
        assertEquals(TicketStatus.RESOLVED, ticket.status)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `same clientRequestId with different content is rejected`() {
        stub(0, stored("Nội dung cũ"))
        org.mockito.Mockito.`when`(support.detail(customer, 42)).thenReturn(legacyTicket())

        assertThrows(ConflictException::class.java) {
            service.sendUserMessage(customer, 42, SendTicketMessageRequest("Nội dung mới", clientRequestId = key))
        }
    }

    @Test
    fun `new clientRequestId performs the insert and the status update`() {
        stub(1, stored("Cần hỗ trợ thêm"))
        org.mockito.Mockito.`when`(support.detail(customer, 42)).thenReturn(legacyTicket())

        val result = service.sendUserMessage(customer, 42, SendTicketMessageRequest("Cần hỗ trợ thêm", clientRequestId = key))

        assertEquals(99L, result.id)
        val insert = messageInserts().single()
        val args = insert.arguments.flatMap { if (it is Array<*>) it.toList() else listOf(it) }
        assertEquals("INSERT INTO support_ticket_messages", (args[0] as String).trim().substringBefore('\n'))
        assertEquals(listOf(42L, 11L, "CUSTOMER", "Cần hỗ trợ thêm", null, key, null), args.drop(1).take(7))
        assertTrue(jdbcCalls.any { (it.arguments.firstOrNull() as? String)?.contains("UPDATE support_tickets") == true })
    }

    @Test
    fun `exact cloudinary folder prefix and version are accepted including nested file names`() {
        val url = "https://res.cloudinary.com/test-cloud/image/upload/v17/app_dat_xe/customer/11/nested/evidence.jpg"
        stub(1, stored("", url))

        val result = service.sendUserMessage(customer, 42, SendTicketMessageRequest("", imageUrl = url, clientRequestId = key))

        assertEquals(url, result.imageUrl)
    }

    @Test
    fun `path traversal query fragment foreign cloud or wrong folder are all rejected`() {
        val attacks = listOf(
            "https://res.cloudinary.com/test-cloud/image/upload/v17/app_dat_xe/customer/11/../customer/99/evidence.jpg",
            "https://res.cloudinary.com/test-cloud/image/upload/v17/app_dat_xe/customer/11/evidence.jpg?x=1",
            "https://res.cloudinary.com/test-cloud/image/upload/v17/app_dat_xe/customer/11/evidence.jpg#frag",
            "https://res.cloudinary.com/other-cloud/image/upload/v17/app_dat_xe/customer/11/evidence.jpg",
            "https://res.cloudinary.com/test-cloud/image/upload/vx/app_dat_xe/customer/11/evidence.jpg",
            "https://res.cloudinary.com/test-cloud/image/upload/v17/datxe/chat/12/evidence.jpg",
            "http://res.cloudinary.com/test-cloud/image/upload/v17/app_dat_xe/customer/11/evidence.jpg",
            "https://res.cloudinary.com.evil.test/test-cloud/image/upload/v17/app_dat_xe/customer/11/evidence.jpg",
        )
        org.mockito.Mockito.`when`(support.detail(customer, 42)).thenReturn(legacyTicket())
        attacks.forEach { url ->
            assertThrows(BadRequestException::class.java) {
                service.sendUserMessage(customer, 42, SendTicketMessageRequest("", imageUrl = url, clientRequestId = key))
            }
        }
        assertTrue(messageInserts().isEmpty())
    }
}
