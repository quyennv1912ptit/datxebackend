package com.nhom15.datxebackend.notification.channel

import com.nhom15.datxebackend.common.websocket.WsEnvelope
import com.nhom15.datxebackend.notification.api.Recipient

interface NotificationChannel {
    fun isAvailable(to: Recipient): Boolean
    fun send(to: Recipient, envelope: WsEnvelope<*>): Boolean
}