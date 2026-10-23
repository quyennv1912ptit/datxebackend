package com.nhom15.datxebackend.chat.repository

import com.nhom15.datxebackend.common.enums.Role
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/** Uses PostgreSQL's partial unique index for race-safe idempotent inserts. */
@Repository
class ChatMessageWriter(private val jdbcTemplate: JdbcTemplate) {
    fun insertIfAbsent(
        conversationId: Long,
        clientMessageId: UUID,
        senderRole: Role,
        senderId: Long,
        messageType: String,
        content: String,
        attachmentUrl: String?,
        publicId: String?,
    ): Long? {
        val customerId = senderId.takeIf { senderRole == Role.CUSTOMER }
        val driverId = senderId.takeIf { senderRole == Role.DRIVER }
        return jdbcTemplate.query(
            """INSERT INTO chat_messages
               (conversation_id, sender_role, sender_customer_id, sender_driver_id, message_type,
                content, attachment_url, cloudinary_public_id, client_message_id, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
               ON CONFLICT (conversation_id, client_message_id) WHERE client_message_id IS NOT NULL
               DO NOTHING RETURNING id""".trimIndent(),
            { row, _ -> row.getLong("id") },
            conversationId,
            senderRole.name,
            customerId,
            driverId,
            messageType,
            content,
            attachmentUrl,
            publicId,
            clientMessageId,
        ).firstOrNull()
    }
}
