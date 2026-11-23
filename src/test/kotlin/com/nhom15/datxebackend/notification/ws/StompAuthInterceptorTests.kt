package com.nhom15.datxebackend.notification.ws

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.notification.api.Recipient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessagingException
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder

class StompAuthInterceptorTests {
    private val verifier = mock(AuthTokenVerifier::class.java)
    private val interceptor = StompAuthInterceptor(verifier)
    private val channel = mock(MessageChannel::class.java)

    @Test
    fun `lowercase bearer scheme is accepted just like the HTTP filter`() {
        `when`(verifier.verify("id-token", Role.CUSTOMER)).thenReturn(Recipient.Customer(9))
        val accessor = connect("bearer id-token", "CUSTOMER")

        interceptor.preSend(message(accessor), channel)

        verify(verifier).verify("id-token", Role.CUSTOMER)
        val principal = accessor.user as? StompPrincipal
        assertNotNull(principal)
        assertEquals("CUSTOMER:9", principal?.name)
    }

    @Test
    fun `a non-bearer scheme is still rejected without calling the verifier`() {
        val message = message(connect("Basic id-token", null))

        assertThrows(MessagingException::class.java) { interceptor.preSend(message, channel) }
        verifyNoInteractions(verifier)
    }

    private fun connect(auth: String, role: String?): StompHeaderAccessor =
        StompHeaderAccessor.create(StompCommand.CONNECT).apply {
            // Keep the same accessor attached to the message so we can assert the principal
            // the interceptor assigns on CONNECT.
            setLeaveMutable(true)
            setNativeHeader("Authorization", auth)
            role?.let { setNativeHeader("X-Role", it) }
            setSessionId("s-1")
        }

    private fun message(accessor: StompHeaderAccessor) =
        MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
}
