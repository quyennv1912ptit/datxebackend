package com.nhom15.datxebackend.support.ticket

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.support.SupportService
import com.nhom15.datxebackend.support.SupportTicket
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

class TicketServiceTests {
    private val support = mock(SupportService::class.java)
    private val tickets = mock(SupportTicketRepository::class.java)
    private val messages = mock(TicketMessageRepository::class.java)
    private val jdbc = mock(JdbcTemplate::class.java)
    private val events = mock(ApplicationEventPublisher::class.java)
    private val service = TicketService(support, tickets, messages, jdbc, events)
    private val customer = AuthUser(11, "customer", Role.CUSTOMER, "Khách", null, null, "ACTIVE")
    private val admin = customer.copy(id = 3, role = Role.ADMIN)

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

    @Test fun `user reply reopens ticket and frees the admin claim`() {
        `when`(support.detail(customer, 42)).thenReturn(legacyTicket())
        `when`(messages.save(any(TicketMessageEntity::class.java))).thenAnswer {
            (it.arguments[0] as TicketMessageEntity).apply { id = 1 }
        }

        val message = service.sendUserMessage(customer, 42, SendTicketMessageRequest("Cần hỗ trợ thêm"))

        assertEquals(TicketSenderRole.CUSTOMER, message.senderRole)
        assertEquals("Cần hỗ trợ thêm", message.body)
        verify(messages).save(any(TicketMessageEntity::class.java))
    }

    @Test fun `admin reply updates status and publishes the push event`() {
        val ticket = entity()
        `when`(tickets.findForUpdate(42)).thenReturn(ticket)
        `when`(messages.save(any(TicketMessageEntity::class.java))).thenAnswer {
            (it.arguments[0] as TicketMessageEntity).apply { id = 2 }
        }

        val message = service.sendAdminMessage(
            admin, 42, SendTicketMessageRequest("Đã xử lý yêu cầu", TicketStatus.WAITING_FOR_USER),
        )

        assertEquals(TicketSenderRole.ADMIN, message.senderRole)
        assertEquals(TicketStatus.WAITING_FOR_USER, ticket.status)
        assertEquals(3L, ticket.handledBy)
        assertEquals("Đã xử lý yêu cầu", ticket.response)
        verify(events).publishEvent(any(TicketReplied::class.java))
    }

    @Test fun `another admin cannot reply while the ticket is in progress`() {
        `when`(tickets.findForUpdate(42)).thenReturn(entity(TicketStatus.IN_PROGRESS, handledBy = 99))

        assertThrows(ConflictException::class.java) {
            service.sendAdminMessage(admin, 42, SendTicketMessageRequest("Tôi chen vào"))
        }
        verify(messages, never()).save(any(TicketMessageEntity::class.java))
    }

    @Test fun `image-only message is accepted and stores the image url`() {
        `when`(support.detail(customer, 42)).thenReturn(legacyTicket())
        `when`(messages.save(any(TicketMessageEntity::class.java))).thenAnswer {
            (it.arguments[0] as TicketMessageEntity).apply { id = 3 }
        }
        val url = "https://res.cloudinary.com/demo/image/upload/v1/app_dat_xe/customer/11/photo.jpg"

        val message = service.sendUserMessage(customer, 42, SendTicketMessageRequest("", imageUrl = url))

        assertEquals("", message.body)
        assertEquals(url, message.imageUrl)
        verify(messages).save(any(TicketMessageEntity::class.java))
    }

    @Test fun `image from another account is rejected`() {
        `when`(support.detail(customer, 42)).thenReturn(legacyTicket())
        val foreign = "https://res.cloudinary.com/demo/image/upload/v1/app_dat_xe/customer/99/photo.jpg"

        assertThrows(BadRequestException::class.java) {
            service.sendUserMessage(customer, 42, SendTicketMessageRequest("", imageUrl = foreign))
        }
        verify(messages, never()).save(any(TicketMessageEntity::class.java))
    }

    @Test fun `empty message is rejected before writing`() {
        `when`(support.detail(customer, 42)).thenReturn(legacyTicket())

        assertThrows(BadRequestException::class.java) {
            service.sendUserMessage(customer, 42, SendTicketMessageRequest("   "))
        }
        verify(messages, never()).save(any(TicketMessageEntity::class.java))
    }
}
