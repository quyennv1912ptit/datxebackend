package com.nhom15.datxebackend.notification.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.nhom15.datxebackend.common.websocket.PayloadType

sealed interface NotificationPayload {
    val rideId: Long? get() = null

    @get:JsonIgnore
    val type: PayloadType

    val title: String get() = type.name
    val body: String get() = type.name
}
