package com.nhom15.datxebackend.notification.dto

import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.support.ticket.TicketStatus

/**
 * Payload FCM cho phản hồi CSKH. Body lấy 50 ký tự đầu theo yêu cầu PRD;
 * app dùng ticketId để mở thẳng TicketChatScreen.
 */
data class TicketReplyPayload(
    val ticketId: Long,
    val ticketCode: String,
    val status: TicketStatus,
    val message: String,
) : NotificationPayload {
    override val type: PayloadType = PayloadType.TICKET_REPLY
    override val title: String = "Hỗ trợ khách hàng: Có phản hồi mới"
    override val body: String = message.ifBlank { "Đã gửi một hình ảnh" }.take(50)
}
