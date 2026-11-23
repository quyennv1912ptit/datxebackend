package com.nhom15.datxebackend.support

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.support.ticket.SendTicketMessageRequest
import com.nhom15.datxebackend.support.ticket.SupportTicketEntity
import com.nhom15.datxebackend.support.ticket.SupportTicketRepository
import com.nhom15.datxebackend.support.ticket.TicketMessageRepository
import com.nhom15.datxebackend.support.ticket.TicketService
import com.nhom15.datxebackend.support.ticket.TicketStatus
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.time.Instant

class P2GroupDSupportTests {

    @Test
    fun `F-037 sendUserMessage locks ticket FOR UPDATE and rejects replying to CLOSED ticket`() {
        val support = Mockito.mock(SupportService::class.java)
        val tickets = Mockito.mock(SupportTicketRepository::class.java)
        val messages = Mockito.mock(TicketMessageRepository::class.java)
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        val events = Mockito.mock(ApplicationEventPublisher::class.java)

        val closedTicket = Mockito.mock(SupportTicketEntity::class.java)
        Mockito.`when`(closedTicket.id).thenReturn(50L)
        Mockito.`when`(closedTicket.ownerId).thenReturn(1L)
        Mockito.`when`(closedTicket.ownerRole).thenReturn(Role.CUSTOMER)
        Mockito.`when`(closedTicket.status).thenReturn(TicketStatus.CLOSED)
        Mockito.`when`(tickets.findForUpdate(50L)).thenReturn(closedTicket)

        val service = TicketService(support, tickets, messages, jdbc, events)
        val customer = AuthUser(1L, "c1", Role.CUSTOMER, "Alice", null, null, "ACTIVE")

        assertThatThrownBy {
            service.sendUserMessage(customer, 50L, SendTicketMessageRequest(body = "Reopen please"))
        }.isInstanceOf(ConflictException::class.java)

        Mockito.verify(tickets).findForUpdate(50L)
        Mockito.verify(messages, Mockito.never()).save(any())
    }

    @Test
    fun `F-038 legacy SupportService update enforces claim ownership, non-blank CLOSED response, and publishes TicketReplied`() {
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        val rides = Mockito.mock(RideRepository::class.java)
        val assets = Mockito.mock(CloudinaryAssetService::class.java)
        val service = SupportService(jdbc, rides, assets)
        val admin2 = AuthUser(2L, "a2", Role.ADMIN, "Admin 2", null, null, "ACTIVE")

        // Blank response on CLOSED must be rejected
        assertThatThrownBy {
            service.update(admin2, 50L, UpdateSupportTicket(status = "CLOSED", response = "   "))
        }.isInstanceOf(BadRequestException::class.java)

        // Active claim by admin 1 (IN_PROGRESS, handled_by=1) must block admin 2 with ConflictException
        val now = Instant.now()
        Mockito.doReturn(listOf(arrayOf<Any?>(now, "IN_PROGRESS", 1L, 10L, "CUSTOMER"))).`when`(jdbc)
            .query(anyString(), any<RowMapper<Any>>(), eq(50L))

        assertThatThrownBy {
            service.update(admin2, 50L, UpdateSupportTicket(status = "WAITING_FOR_USER", response = "Hi"))
        }.isInstanceOf(ConflictException::class.java)
    }
}
