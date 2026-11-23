package com.nhom15.datxebackend.notification.channel

import com.nhom15.datxebackend.common.websocket.WsEnvelope
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.ws.AuthTokenVerifier
import com.nhom15.datxebackend.notification.ws.StompPrincipal
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.user.SimpUserRegistry
import org.springframework.stereotype.Component

@Component
class StompChannel @Autowired constructor(
    private val userRegistry: SimpUserRegistry,
    private val messaging: SimpMessagingTemplate,
    private val tokenVerifier: AuthTokenVerifier? = null,
) : NotificationChannel {
    override fun isAvailable(to: Recipient): Boolean {
        val user = userRegistry.getUser(to.key) ?: return false
        if (tokenVerifier != null) {
            val principal = user.principal as? StompPrincipal ?: return false
            val active = try {
                tokenVerifier.isSessionActive(principal, token = null, checkRemoteRevocation = false)
            } catch (_: Exception) {
                false
            }
            if (!active) return false
        }
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
        val dest = "/queue/trip"
        messaging.convertAndSendToUser(to.key, dest, envelope)
        return true
    }

    fun sendIfOnline(to: Recipient, envelope: WsEnvelope<*>): Boolean = send(to, envelope)
}
