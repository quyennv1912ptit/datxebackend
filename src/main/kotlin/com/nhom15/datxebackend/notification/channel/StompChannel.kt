package com.nhom15.datxebackend.notification.channel

import com.nhom15.datxebackend.common.websocket.WsEnvelope
import com.nhom15.datxebackend.notification.api.Recipient
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.user.SimpUserRegistry
import org.springframework.stereotype.Component

@Component
class StompChannel(
    private val userRegistry: SimpUserRegistry,
    private val messaging: SimpMessagingTemplate
) : NotificationChannel {
    override fun isAvailable(to: Recipient): Boolean {
        val user = userRegistry.getUser(to.key) ?: return false
        return user.sessions.any { session ->
            session.subscriptions.any {
                it.destination.startsWith("/user/queue/trip")
            }
        }
    }

    override fun send(
        to: Recipient,
        envelope: WsEnvelope<*>
    ): Boolean {
        if (!isAvailable(to)) return false
        val dest = "/user/queue/trip"
        messaging.convertAndSendToUser(to.key, dest, envelope)
        return true
    }

    fun sendIfOnline(to: Recipient, envelope: WsEnvelope<*>): Boolean = send(to, envelope)
}
