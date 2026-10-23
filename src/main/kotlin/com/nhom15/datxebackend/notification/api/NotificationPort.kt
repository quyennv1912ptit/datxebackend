package com.nhom15.datxebackend.notification.api

import com.nhom15.datxebackend.notification.dto.NotificationPayload

interface NotificationPort {
    fun send(to: Recipient, payload: NotificationPayload): DeliveryResult
}

enum class DeliveryResult { SENT_WS, SENT_PUSH, UNREACHABLE }