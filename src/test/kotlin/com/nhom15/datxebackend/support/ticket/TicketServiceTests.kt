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

class TicketServiceTests {
    private var insertRows = 1
    private var storedMessage: TicketMessageEntity? = null
    private val jdbcCalls = mutableListOf<InvocationOnMock>()
    private val published = mutableListOf<Any>()

    // Kotlin non-null params + Mockito any()/eq() => null NPE / leftover matcher. Dùng defaultAnswer ghi nhận lời gọi.
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
    private val service = TicketService(support, tickets, messages, jdbc, events, "cloudinary://key:secret@demo")
    private val customer = AuthUser(11, "customer", Role.CUSTOMER, "Khách", null, null, "ACTIVE")
    private val admin = customer.copy(id = 3, role = Role.ADMIN)

    private fun InvocationOnMock.isMessageInsert(): Boolean =
        method.name == "update" && (arguments.firstOrNull() as? String)?.contains("INSERT INTO support_ticket_messages") == true

    private fun messageInserts() = jdbcCalls.filter { it.isMessageInsert() }

    private fun entity(status: TicketStatus = TicketStatus.OPEN, handledBy: Long? = null) = SupportTicketEntity(
        ownerId = 11,
        ownerRole = Role.CUSTOMER,
        clientRequestId = UUID.randomUUID(),
        category = "OTHER",
        description = "Cần hỗ trợ",
        status = status,
        handledBy = handledBy,
        id = 42,
    )

    private fun legacyTicket() = SupportTicket(
        id = 42, code = "HT-42", rideId = null, category = "OTHER", description = "Cần hỗ trợ",
        status = "OPEN", response = null, images = emptyList(), createdAt = Instant.now(), updatedAt = Instant.now(),
        ownerId = 11, ownerRole = "CUSTOMER",
    )

    private fun persisted(
        ticketId: Long = 42,
        senderId: Long = 11,
        role: TicketSenderRole = TicketSenderRole.CUSTOMER,
        body: String,
        imageUrl: String? = null,
        key: UUID = UUID.randomUUID(),
        id: Long = 1,
    ) = TicketMessageEntity(ticketId, senderId, role, body, imageUrl, key).apply { this.id = id }

    private fun stubInsert(message: TicketMessageEntity, rows: Int = 1) {
        insertRows = rows
        storedMessage = message
    }

    @Test fun `user reply reopens ticket and frees the admin claim`() {
        org.mockito.Mockito.`when`(support.detail(customer, 42)).thenReturn(legacyTicket())
        val message = persisted(body = "Cần hỗ trợ thêm")
        stubInsert(message)

        val result = service.sendUserMessage(
            customer, 42, SendTicketMessageRequest("Cần hỗ trợ thêm", clientRequestId = message.clientRequestId),
        )

        assertEquals(TicketSenderRole.CUSTOMER, result.senderRole)
        assertEquals("Cần hỗ trợ thêm", result.body)
        assertEquals(1, messageInserts().size)
    }

    @Test fun `admin reply updates status and publishes the push event`() {
        val ticket = entity()
        org.mockito.Mockito.`when`(tickets.findForUpdate(42)).thenReturn(ticket)
        val message = persisted(senderId = 3, role = TicketSenderRole.ADMIN, body = "Đã xử lý yêu cầu")
        stubInsert(message)

        val result = service.sendAdminMessage(
            admin, 42,
            SendTicketMessageRequest("Đã xử lý yêu cầu", TicketStatus.WAITING_FOR_USER, clientRequestId = message.clientRequestId),
        )

        assertEquals(TicketSenderRole.ADMIN, result.senderRole)
        assertEquals(TicketStatus.WAITING_FOR_USER, ticket.status)
        assertEquals(3L, ticket.handledBy)
        assertEquals("Đã xử lý yêu cầu", ticket.response)
        assertTrue(published.any { it is TicketReplied })
    }

    @Test fun `another admin cannot reply while the ticket is in progress`() {
        org.mockito.Mockito.`when`(tickets.findForUpdate(42)).thenReturn(entity(TicketStatus.IN_PROGRESS, handledBy = 99))

        assertThrows(ConflictException::class.java) {
            service.sendAdminMessage(admin, 42, SendTicketMessageRequest("Tôi chen vào"))
        }
        assertTrue(messageInserts().isEmpty())
    }

    @Test fun `image-only message is accepted and stores the image url`() {
        org.mockito.Mockito.`when`(support.detail(customer, 42)).thenReturn(legacyTicket())
        val url = "https://res.cloudinary.com/demo/image/upload/v1/app_dat_xe/customer/11/photo.jpg"
        val message = persisted(body = "", imageUrl = url)
        stubInsert(message)

        val result = service.sendUserMessage(
            customer, 42, SendTicketMessageRequest("", imageUrl = url, clientRequestId = message.clientRequestId),
        )

        assertEquals("", result.body)
        assertEquals(url, result.imageUrl)
        assertEquals(1, messageInserts().size)
    }

    @Test fun `image from another account is rejected`() {
        org.mockito.Mockito.`when`(support.detail(customer, 42)).thenReturn(legacyTicket())
        val foreign = "https://res.cloudinary.com/demo/image/upload/v1/app_dat_xe/customer/99/photo.jpg"

        assertThrows(BadRequestException::class.java) {
            service.sendUserMessage(customer, 42, SendTicketMessageRequest("", imageUrl = foreign))
        }
        assertTrue(messageInserts().isEmpty())
    }

    @Test fun `empty message is rejected before writing`() {
        org.mockito.Mockito.`when`(support.detail(customer, 42)).thenReturn(legacyTicket())

        assertThrows(BadRequestException::class.java) {
            service.sendUserMessage(customer, 42, SendTicketMessageRequest("   "))
        }
        assertTrue(messageInserts().isEmpty())
    }
}
