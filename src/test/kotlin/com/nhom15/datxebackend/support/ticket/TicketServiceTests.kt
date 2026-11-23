package com.nhom15.datxebackend.support.ticket

import com.cloudinary.Cloudinary
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.support.SupportService
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
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

    @Test fun `user reply reopens ticket and frees the admin claim`() {
        `when`(tickets.findForUpdate(42)).thenReturn(entity())
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
        `when`(tickets.findForUpdate(42)).thenReturn(entity())
        `when`(messages.save(any(TicketMessageEntity::class.java))).thenAnswer {
            (it.arguments[0] as TicketMessageEntity).apply { id = 3 }
        }
        val url = "https://res.cloudinary.com/demo/image/upload/v1/app_dat_xe/customer/11/photo.jpg"
        val assets = CloudinaryAssetService(Cloudinary(mapOf("cloud_name" to "demo")))
        doAnswer { assets.validateOwnedImage(customer, url) }.`when`(support).validateMessageImage(customer, url)

        val message = service.sendUserMessage(customer, 42, SendTicketMessageRequest("", imageUrl = url))

        assertEquals("", message.body)
        assertEquals(url, message.imageUrl)
        verify(support).validateMessageImage(customer, url)
        verify(messages).save(any(TicketMessageEntity::class.java))
    }

    @Test fun `image from another account is rejected`() {
        `when`(tickets.findForUpdate(42)).thenReturn(entity())
        val foreign = "https://res.cloudinary.com/demo/image/upload/v1/app_dat_xe/customer/99/photo.jpg"
        val assets = CloudinaryAssetService(Cloudinary(mapOf("cloud_name" to "demo")))
        doAnswer { assets.validateOwnedImage(customer, foreign) }.`when`(support).validateMessageImage(customer, foreign)

        assertThrows(BadRequestException::class.java) {
            service.sendUserMessage(customer, 42, SendTicketMessageRequest("", imageUrl = foreign))
        }
        verify(messages, never()).save(any(TicketMessageEntity::class.java))
    }

    @Test fun `empty message is rejected before writing`() {
        `when`(tickets.findForUpdate(42)).thenReturn(entity())

        assertThrows(BadRequestException::class.java) {
            service.sendUserMessage(customer, 42, SendTicketMessageRequest("   "))
        }
        verify(messages, never()).save(any(TicketMessageEntity::class.java))
    }

    @Test fun `lost admin reply response can be retried without another save or event`() {
        val ticket = entity()
        `when`(tickets.findForUpdate(42)).thenReturn(ticket)
        val key = UUID.randomUUID()
        val reply = SendTicketMessageRequest("Đã xử lý", TicketStatus.CLOSED, clientMessageId = key)
        var previous: TicketMessageEntity? = null
        `when`(messages.findByTicketIdAndSenderIdAndSenderRoleAndClientMessageId(42, admin.id, TicketSenderRole.ADMIN, key)).thenAnswer {
            previous
        }
        `when`(messages.save(any(TicketMessageEntity::class.java))).thenAnswer {
            (it.arguments[0] as TicketMessageEntity).apply { id = 1; previous = this }
        }

        val first = service.sendAdminMessage(admin, 42, reply)
        assertEquals(first, service.sendAdminMessage(admin, 42, reply))
        assertThrows(ConflictException::class.java) {
            service.sendAdminMessage(admin, 42, reply.copy(status = TicketStatus.OPEN))
        }
        verify(messages).save(any(TicketMessageEntity::class.java))
        verify(events).publishEvent(any(TicketReplied::class.java))
    }
}
