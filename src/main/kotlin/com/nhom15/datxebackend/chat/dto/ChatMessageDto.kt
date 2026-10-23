package com.nhom15.datxebackend.chat.dto

import com.nhom15.datxebackend.chat.entity.ChatMessage
import java.time.Instant
import java.util.UUID

data class ChatMessageDto(
    val id: Long,
    val clientMessageId: UUID?,
    val senderRole: String,
    val messageType: String,
    val content: String?,
    val attachmentUrl: String?,
    val createdAt: Instant,
    val readAt: Instant?,
) {
    companion object {
        fun from(message: ChatMessage) = ChatMessageDto(
            id = message.id,
            clientMessageId = message.clientMessageId,
            senderRole = message.senderRole,
            messageType = message.messageType,
            content = message.content.takeIf(String::isNotBlank),
            attachmentUrl = message.attachmentUrl,
            createdAt = message.createdAt,
            readAt = message.readAt,
        )
    }
}

data class MessagePage(
    val rideId: Long,
    val messages: List<ChatMessageDto>,
    val hasMore: Boolean,
)

data class MarkReadRequest(val upToMessageId: Long)

data class MarkReadResponse(
    val rideId: Long,
    val markedCount: Int,
)

data class ChatMessageCreatedPayload(
    val rideId: Long,
    val message: ChatMessageDto,
    val senderName: String,
)

data class ChatMessageFcmPayload(
    val rideId: Long,
    val message: ChatMessageFcmMessage,
    val senderName: String,
)

data class ChatMessageFcmMessage(
    val id: Long,
    val senderRole: String,
    val messageType: String,
    val content: String,
    val createdAt: Instant,
)

data class ChatReadPayload(
    val rideId: Long,
    val upToMessageId: Long,
)
