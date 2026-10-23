package com.nhom15.datxebackend.notification.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class UnreadNotificationCountResponse(
    @JsonProperty("unread_count") val unreadCount: Long,
)

data class ReadAllNotificationsResponse(
    @JsonProperty("updated_count") val updatedCount: Int,
)
