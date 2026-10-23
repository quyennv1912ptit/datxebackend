package com.nhom15.datxebackend.notification.service

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.notification.dto.NotificationHistoryItem
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class NotificationHistoryService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun list(actor: AuthUser): List<NotificationHistoryItem> {
        val recipientColumn = when (actor.role) {
            Role.CUSTOMER -> "recipient_customer_id"
            Role.DRIVER -> "recipient_driver_id"
            Role.ADMIN -> throw ForbiddenException(message = "Admin không có lịch sử thông báo cá nhân")
        }
        return jdbcTemplate.query(
            """SELECT id, notification_type, data, created_at, read_at
               FROM notifications WHERE $recipientColumn = ?
               ORDER BY created_at DESC, id DESC LIMIT 100""".trimIndent(),
            { row, _ ->
                val data = objectMapper.readTree(row.getString("data"))
                val messageId = data.path("messageId").stringValue()
                NotificationHistoryItem(
                    id = row.getLong("id"),
                    messageId = messageId ?: row.getLong("id").toString(),
                    type = row.getString("notification_type"),
                    payload = data.path("payload"),
                    createdAt = row.getTimestamp("created_at").toInstant(),
                    readAt = row.getTimestamp("read_at")?.toInstant(),
                )
            },
            actor.id,
        )
    }
}
