package com.nhom15.datxebackend.chat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "chat_messages")
class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(name = "conversation_id", nullable = false)
    var conversationId: Long = 0

    @Column(name = "sender_role", nullable = false, length = 10)
    var senderRole: String = ""

    @Column(name = "sender_customer_id")
    var senderCustomerId: Long? = null

    @Column(name = "sender_driver_id")
    var senderDriverId: Long? = null

    @Column(name = "message_type", nullable = false, length = 20)
    var messageType: String = ChatMessageType.TEXT.name

    @Column(name = "content", nullable = false, columnDefinition = "text")
    var content: String = ""

    @Column(name = "attachment_url", columnDefinition = "text")
    var attachmentUrl: String? = null

    @Column(name = "cloudinary_public_id", columnDefinition = "text")
    var cloudinaryPublicId: String? = null

    @Column(name = "client_message_id")
    var clientMessageId: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "read_at")
    var readAt: Instant? = null
}
