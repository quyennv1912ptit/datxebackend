package com.nhom15.datxebackend.support.ticket

import com.nhom15.datxebackend.common.enums.Role

/** Phát sau khi admin trả lời; listener gửi FCM cho đúng chủ ticket. */
data class TicketReplied(
    val ticketId: Long,
    val ticketCode: String,
    val ownerId: Long,
    val ownerRole: Role,
    val status: TicketStatus,
    val message: String,
)
