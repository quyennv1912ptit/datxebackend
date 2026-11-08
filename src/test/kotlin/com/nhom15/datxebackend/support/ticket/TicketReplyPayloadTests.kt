package com.nhom15.datxebackend.support.ticket

import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.notification.dto.TicketReplyPayload
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TicketReplyPayloadTests {
    @Test fun `ticket push has the required title and truncates the body to fifty characters`() {
        val payload = TicketReplyPayload(42, "HT-42", TicketStatus.WAITING_FOR_USER, "x".repeat(80))
        assertEquals(PayloadType.TICKET_REPLY, payload.type)
        assertEquals("Hỗ trợ khách hàng: Có phản hồi mới", payload.title)
        assertEquals(50, payload.body.length)
        assertEquals(42L, payload.ticketId)
    }
}
