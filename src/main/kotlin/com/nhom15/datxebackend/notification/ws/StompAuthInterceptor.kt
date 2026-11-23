package com.nhom15.datxebackend.notification.ws

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.notification.api.Recipient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessagingException
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.stereotype.Component
import java.security.Principal
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

class StompPrincipal(
    val recipient: Recipient,
    val firebaseUid: String? = null,
    val role: Role = when (recipient) {
        is Recipient.Customer -> Role.CUSTOMER
        is Recipient.Driver -> Role.DRIVER
    },
) : Principal {
    override fun getName(): String = recipient.key
}

@Component
class StompAuthInterceptor @Autowired constructor(
    private val tokenVerifier: AuthTokenVerifier,
    @Value("\${app.stomp.revalidation-interval-ms:30000}")
    private val revalidationIntervalMs: Long = DEFAULT_REVALIDATION_INTERVAL_MS,
) : ChannelInterceptor {

    private data class SessionAuthState(
        val token: String,
        @Volatile var lastRemoteCheckMillis: Long,
    )

    internal var clock: Clock = Clock.systemUTC()
    private val sessions = ConcurrentHashMap<String, SessionAuthState>()
    private val log = LoggerFactory.getLogger(javaClass)

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
            ?: return message

        when (accessor.command) {
            StompCommand.CONNECT -> authenticate(accessor)
            StompCommand.SUBSCRIBE -> {
                requireAuthenticated(accessor)
                revalidateSession(accessor)
                requireAllowedDestination(accessor)
            }

            StompCommand.SEND -> {
                requireAuthenticated(accessor)
                revalidateSession(accessor)
                requireAllowedSendDestination(accessor)
            }

            StompCommand.DISCONNECT -> accessor.sessionId?.let(sessions::remove)
            else -> Unit
        }
        return message
    }

    private fun authenticate(accessor: StompHeaderAccessor) {
        val token = extractBearerToken(accessor)
            ?: throw MessagingException("Thiếu header Authorization")
        val roleHint = extractRoleHint(accessor)

        val identity = try {
            tokenVerifier.verifyIdentity(token, roleHint)
        } catch (e: Exception) {
            log.debug("Xác thực token STOMP thất bại: {}", e.message)   // không log token
            null
        } ?: throw MessagingException("Token không hợp lệ hoặc đã hết hạn")

        accessor.user = StompPrincipal(identity.recipient, identity.firebaseUid, identity.role)
        accessor.sessionId?.let { sessionId ->
            sessions[sessionId] = SessionAuthState(token, clock.millis())
        }
        log.info("STOMP CONNECT ok principal={} session={}", identity.recipient.key, accessor.sessionId)
    }

    private fun revalidateSession(accessor: StompHeaderAccessor) {
        val principal = accessor.user as? StompPrincipal
            ?: throw MessagingException("Chưa đăng nhập")
        val sessionId = accessor.sessionId
        val state = sessionId?.let(sessions::get)
        val now = clock.millis()
        val checkRemote = state != null && revalidationIntervalMs >= 0 &&
            (now - state.lastRemoteCheckMillis) >= revalidationIntervalMs

        val active = try {
            tokenVerifier.isSessionActive(principal, state?.token, checkRemote)
        } catch (_: Exception) {
            false
        }
        if (!active) {
            sessionId?.let(sessions::remove)
            throw MessagingException("Phiên đăng nhập đã hết hạn hoặc bị thu hồi")
        }
        if (checkRemote && state != null) {
            state.lastRemoteCheckMillis = now
        }
    }

    private fun requireAuthenticated(accessor: StompHeaderAccessor) {
        if (accessor.user == null) {
            throw MessagingException("Chưa đăng nhập")
        }
    }

    private fun requireAllowedDestination(accessor: StompHeaderAccessor) {
        val destination = accessor.destination
        if (destination == null || !destination.startsWith(ALLOWED_SUBSCRIBE_PREFIX)) {
            throw MessagingException("Không được phép subscribe $destination")
        }
    }

    private fun requireAllowedSendDestination(accessor: StompHeaderAccessor) {
        val destination = accessor.destination
        if (destination != ALLOWED_DRIVER_LOCATION_SEND_DESTINATION) {
            throw MessagingException("Không được phép gửi tới $destination")
        }
        val recipient = (accessor.user as? StompPrincipal)?.recipient
        if (recipient !is Recipient.Driver) {
            throw MessagingException("Chỉ tài xế đã xác thực mới được gửi vị trí")
        }
    }

    private fun extractBearerToken(accessor: StompHeaderAccessor): String? {
        val raw = accessor.getFirstNativeHeader(AUTH_HEADER) ?: return null
        return raw.removePrefix(BEARER_PREFIX).trim().takeIf { it.isNotEmpty() }
    }

    private fun extractRoleHint(accessor: StompHeaderAccessor): Role? {
        val raw = accessor.getFirstNativeHeader(ROLE_HEADER)
            ?: accessor.getFirstNativeHeader(ROLE_PARAM)
            ?: return null
        return when (raw.trim().uppercase()) {
            "CUSTOMER", "USER" -> Role.CUSTOMER
            "DRIVER" -> Role.DRIVER
            else -> throw MessagingException("X-Role phải là CUSTOMER hoặc DRIVER")
        }
    }

    private companion object {
        const val AUTH_HEADER = "Authorization"
        const val ROLE_HEADER = "X-Role"
        const val ROLE_PARAM = "role"
        const val BEARER_PREFIX = "Bearer "

        const val ALLOWED_SUBSCRIBE_PREFIX = "/user/queue/"
        const val ALLOWED_DRIVER_LOCATION_SEND_DESTINATION = "/app/location"
        const val DEFAULT_REVALIDATION_INTERVAL_MS = 30_000L
    }
}
