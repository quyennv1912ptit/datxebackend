package com.nhom15.datxebackend.chat.event

import com.nhom15.datxebackend.notification.api.Recipient

data class ChatMessageCreated(
    val messageId: Long,
    val conversationId: Long,
    val rideId: Long,
    val recipient: Recipient,
)
