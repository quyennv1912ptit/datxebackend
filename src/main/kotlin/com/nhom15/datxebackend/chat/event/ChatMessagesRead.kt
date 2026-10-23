package com.nhom15.datxebackend.chat.event

import com.nhom15.datxebackend.notification.api.Recipient

data class ChatMessagesRead(
    val rideId: Long,
    val upToMessageId: Long,
    val recipient: Recipient,
)
