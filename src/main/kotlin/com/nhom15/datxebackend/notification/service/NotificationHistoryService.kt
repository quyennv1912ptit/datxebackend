package com.nhom15.datxebackend.notification.service

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.notification.dto.NotificationHistoryItem
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Service
class NotificationHistoryService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(actor: AuthUser): List<NotificationHistoryItem> {
        val recipientColumn = when (actor.role) {
            Role.CUSTOMER -> "recipient_customer_id"
            Role.DRIVER -> "recipient_driver_id"
            Role.ADMIN -> throw ForbiddenException(message = "Admin không có lịch sử thông báo cá nhân")
        }
        return jdbcTemplate.query(
            """SELECT id, notification_type, data, title, body, created_at, read_at
               FROM notifications WHERE $recipientColumn = ?
               ORDER BY created_at DESC, id DESC LIMIT 100""".trimIndent(),
            { row, _ ->
                toItem(
                    id = row.getLong("id"),
                    type = row.getString("notification_type"),
                    rawData = row.getString("data"),
                    title = row.getString("title"),
                    body = row.getString("body"),
                    createdAt = row.getTimestamp("created_at").toInstant(),
                    readAt = row.getTimestamp("read_at")?.toInstant(),
                )
            },
            actor.id,
        )
    }

    /** Chuyển một dòng trong DB thành DTO; không bao giờ ném lỗi vì dữ liệu của dòng đó. */
    internal fun toItem(
        id: Long,
        type: String,
        rawData: String?,
        title: String?,
        body: String?,
        createdAt: java.time.Instant,
        readAt: java.time.Instant?,
    ): NotificationHistoryItem {
        val data = parseData(rawData, id)
        return NotificationHistoryItem(
            id = id,
            messageId = data.textOrNull("messageId") ?: id.toString(),
            type = type,
            payload = data.payloadOrFallback(title = title, body = body),
            createdAt = createdAt,
            readAt = readAt,
        )
    }

    /**
     * Đọc cột `data` một cách phòng thủ: JSON hỏng hoặc NULL chỉ ảnh hưởng tới đúng
     * bản ghi đó, không được làm hỏng cả danh sách thông báo.
     */
    private fun parseData(raw: String?, id: Long): JsonNode =
        try {
            if (raw.isNullOrBlank()) objectMapper.createObjectNode() else objectMapper.readTree(raw)
        } catch (ex: Exception) {
            log.warn("Bỏ qua JSON không hợp lệ của thông báo id={}", id, ex)
            objectMapper.createObjectNode()
        }

    /** `stringValue()` ném lỗi nếu field không phải string (hoặc không tồn tại) nên phải kiểm tra trước. */
    private fun JsonNode.textOrNull(field: String): String? {
        val node = path(field)
        return when {
            node.isString -> node.stringValue()
            node.isValueNode && !node.isNull -> node.asString()
            else -> null
        }
    }

    /**
     * Payload rỗng/không phải object (dữ liệu cũ, thông báo tạo tay) sẽ được thay bằng
     * nội dung lấy từ hai cột `title`/`body` để app vẫn hiển thị được nội dung.
     */
    private fun JsonNode.payloadOrFallback(title: String?, body: String?): JsonNode {
        val payload = path("payload")
        if (payload.isObject && !payload.isEmpty) return payload
        val fallback = objectMapper.createObjectNode()
        title?.takeIf { it.isNotBlank() }?.let { fallback.put("title", it) }
        body?.takeIf { it.isNotBlank() }?.let { fallback.put("body", it) }
        return fallback
    }
}
