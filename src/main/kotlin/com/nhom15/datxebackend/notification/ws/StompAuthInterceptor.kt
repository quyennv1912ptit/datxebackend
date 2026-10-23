package com.nhom15.datxebackend.notification.ws

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.notification.api.Recipient
import org.slf4j.LoggerFactory
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessagingException
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.stereotype.Component
import java.security.Principal

class StompPrincipal(val recipient: Recipient) : Principal {
    override fun getName(): String = recipient.key
}

@Component
class StompAuthInterceptor(
    private val tokenVerifier: AuthTokenVerifier
) : ChannelInterceptor {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
            ?: return message

        when (accessor.command) {
            StompCommand.CONNECT -> authenticate(accessor)
            StompCommand.SUBSCRIBE -> {
                requireAuthenticated(accessor)
                requireAllowedDestination(accessor)
            }

            StompCommand.SEND -> requireAuthenticated(accessor)
            else -> Unit
        }
        return message
    }

    private fun authenticate(accessor: StompHeaderAccessor) {
        val token = extractBearerToken(accessor)
            ?: throw MessagingException("Thiếu header Authorization")
        val roleHint = extractRoleHint(accessor)

        val recipient = try {
            tokenVerifier.verify(token, roleHint)
        } catch (e: Exception) {
            log.debug("Xác thực token STOMP thất bại: {}", e.message)   // không log token
            null
        } ?: throw MessagingException("Token không hợp lệ hoặc đã hết hạn")

        accessor.user = StompPrincipal(recipient)
        log.info("STOMP CONNECT ok principal={} session={}", recipient.key, accessor.sessionId)
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
    }
}
