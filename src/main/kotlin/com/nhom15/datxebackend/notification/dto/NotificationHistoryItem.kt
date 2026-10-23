package com.nhom15.datxebackend.notification.dto

import tools.jackson.databind.JsonNode
import java.time.Instant

data class NotificationHistoryItem(
    val id: Long,
    val messageId: String,
    val type: String,
    val payload: JsonNode,
    val createdAt: Instant,
    val readAt: Instant?,
)
