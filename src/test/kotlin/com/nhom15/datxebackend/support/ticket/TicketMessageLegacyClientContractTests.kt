package com.nhom15.datxebackend.support.ticket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * App khách/tài xế hiện tại gửi {body, status?, imageUrl?} — chưa có clientRequestId.
 * Đảm bảo request cũ vẫn deserialize được (server sinh UUID) thay vì trả 400.
 */
class TicketMessageLegacyClientContractTests {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `legacy client payload without clientRequestId still deserializes`() {
        val request: SendTicketMessageRequest = mapper.readValue(
            """{"body":"Cần hỗ trợ thêm","status":"WAITING_FOR_USER"}""",
            SendTicketMessageRequest::class.java,
        )
        assertEquals("Cần hỗ trợ thêm", request.body)
        assertTrue(request.clientRequestId.toString().isNotBlank())
    }

    @Test
    fun `explicit clientRequestId from newer clients is honoured`() {
        val request: SendTicketMessageRequest = mapper.readValue(
            """{"body":"x","clientRequestId":"11111111-2222-3333-4444-555555555555"}""",
            SendTicketMessageRequest::class.java,
        )
        assertEquals("11111111-2222-3333-4444-555555555555", request.clientRequestId.toString())
    }
}
