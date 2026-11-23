package com.nhom15.datxebackend.auth.security

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import com.nhom15.datxebackend.auth.service.AuthService
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.AuthenticatedUserProvider
import com.nhom15.datxebackend.notification.ws.FirebaseAuthTokenVerifier
import com.nhom15.datxebackend.notification.ws.StompAuthInterceptor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.messaging.MessagingException
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ExecutorSubscribableChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.nio.charset.StandardCharsets
import java.security.Principal
import java.util.concurrent.atomic.AtomicReference

class AuthRevocationLifecycleTests {

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `FirebaseAuthFilter rejects revoked Firebase ID token using checkRevoked=true`() {
        val firebaseAuth = Mockito.mock(FirebaseAuth::class.java)
        val authService = Mockito.mock(AuthService::class.java)
        val token = Mockito.mock(FirebaseToken::class.java)
        Mockito.`when`(token.uid).thenReturn("uid-1")

        // Simulate unrevoked check passing while checkRevoked=true rejects
        Mockito.`when`(firebaseAuth.verifyIdToken("revoked-token")).thenReturn(token)
        Mockito.`when`(firebaseAuth.verifyIdToken("revoked-token", true))
            .thenThrow(Mockito.mock(FirebaseAuthException::class.java))
        Mockito.`when`(authService.findAuthenticatedUser("uid-1", Role.CUSTOMER))
            .thenReturn(AuthUser(1L, "uid-1", Role.CUSTOMER, "Alice", null, null, "ACTIVE"))

        val filter = FirebaseAuthFilter(firebaseAuth, authService)
        val request = MockHttpServletRequest("GET", "/api/customer/profile").apply {
            addHeader("Authorization", "Bearer revoked-token")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        Mockito.verify(firebaseAuth).verifyIdToken("revoked-token", true)
    }

    @Test
    fun `STOMP CONNECT rejects revoked Firebase ID token using checkRevoked=true`() {
        val firebaseAuth = Mockito.mock(FirebaseAuth::class.java)
        val userProvider = Mockito.mock(AuthenticatedUserProvider::class.java)
        val token = Mockito.mock(FirebaseToken::class.java)
        Mockito.`when`(token.uid).thenReturn("uid-driver-10")

        Mockito.`when`(firebaseAuth.verifyIdToken("revoked-stomp-token")).thenReturn(token)
        Mockito.`when`(firebaseAuth.verifyIdToken("revoked-stomp-token", true))
            .thenThrow(Mockito.mock(FirebaseAuthException::class.java))
        Mockito.`when`(userProvider.findAuthenticatedUser("uid-driver-10", Role.DRIVER))
            .thenReturn(AuthUser(10L, "uid-driver-10", Role.DRIVER, "Driver 10", null, null, "ONLINE"))

        val verifier = FirebaseAuthTokenVerifier(firebaseAuth, userProvider)
        val interceptor = StompAuthInterceptor(verifier)
        val channel = ExecutorSubscribableChannel()

        assertThatThrownBy {
            connect(interceptor, channel, "sess-revoked", "revoked-stomp-token", "DRIVER")
        }.isInstanceOf(MessagingException::class.java)

        Mockito.verify(firebaseAuth).verifyIdToken("revoked-stomp-token", true)
    }

    @Test
    fun `open STOMP session blocks suspended or deleted user immediately after cache eviction without Firebase calls per GPS frame`() {
        val firebaseAuth = Mockito.mock(FirebaseAuth::class.java)
        val token = Mockito.mock(FirebaseToken::class.java)
        Mockito.`when`(token.uid).thenReturn("uid-driver-10")
        Mockito.`when`(firebaseAuth.verifyIdToken("valid-driver-token", true)).thenReturn(token)

        val cache = AuthenticatedUserCache(ttlMillis = 3_000L)
        val currentAccount = AtomicReference<AuthUser?>(
            AuthUser(10L, "uid-driver-10", Role.DRIVER, "Driver 10", null, null, "ONLINE"),
        )
        val userProvider = object : AuthenticatedUserProvider {
            override fun findAuthenticatedUser(firebaseUid: String, roleHint: Role?): AuthUser? =
                if (roleHint == null) currentAccount.get()
                else cache.get(firebaseUid, roleHint) { currentAccount.get() }
        }

        val verifier = FirebaseAuthTokenVerifier(firebaseAuth, userProvider)
        val interceptor = StompAuthInterceptor(verifier)
        val channel = ExecutorSubscribableChannel()

        val principal = connect(interceptor, channel, "sess-drv-10", "valid-driver-token", "DRIVER")

        // Multiple GPS frames succeed without extra Firebase network calls
        repeat(5) {
            send(interceptor, channel, "sess-drv-10", principal, "/app/location", """{"lat":21.0,"lng":105.8}""")
        }
        Mockito.verify(firebaseAuth, Mockito.times(1)).verifyIdToken("valid-driver-token", true)

        // User is deleted or suspended and cache is evicted
        currentAccount.set(null)
        cache.evict("uid-driver-10")

        // Open session must immediately reject SEND and SUBSCRIBE
        assertThatThrownBy {
            send(interceptor, channel, "sess-drv-10", principal, "/app/location", """{"lat":21.0,"lng":105.8}""")
        }.isInstanceOf(MessagingException::class.java)

        assertThatThrownBy {
            subscribe(interceptor, channel, "sess-drv-10", principal, "/user/queue/trip")
        }.isInstanceOf(MessagingException::class.java)
    }

    @Test
    fun `open STOMP session fails closed on role mismatch or provider error`() {
        val firebaseAuth = Mockito.mock(FirebaseAuth::class.java)
        val token = Mockito.mock(FirebaseToken::class.java)
        Mockito.`when`(token.uid).thenReturn("uid-driver-10")
        Mockito.`when`(firebaseAuth.verifyIdToken("valid-driver-token", true)).thenReturn(token)

        val mode = AtomicReference("ACTIVE_DRIVER")
        val userProvider = object : AuthenticatedUserProvider {
            override fun findAuthenticatedUser(firebaseUid: String, roleHint: Role?): AuthUser? =
                when (mode.get()) {
                    "ACTIVE_DRIVER" -> AuthUser(10L, "uid-driver-10", Role.DRIVER, "Driver 10", null, null, "ONLINE")
                    "WRONG_ROLE" -> AuthUser(10L, "uid-driver-10", Role.CUSTOMER, "Customer 10", null, null, "ACTIVE")
                    else -> throw IllegalStateException("DB unavailable")
                }
        }

        val verifier = FirebaseAuthTokenVerifier(firebaseAuth, userProvider)
        val interceptor = StompAuthInterceptor(verifier)
        val channel = ExecutorSubscribableChannel()

        val principal = connect(interceptor, channel, "sess-drv-10", "valid-driver-token", "DRIVER")
        send(interceptor, channel, "sess-drv-10", principal, "/app/location", """{"lat":21.0,"lng":105.8}""")

        // Role changed -> rejected
        mode.set("WRONG_ROLE")
        assertThatThrownBy {
            send(interceptor, channel, "sess-drv-10", principal, "/app/location", """{"lat":21.0,"lng":105.8}""")
        }.isInstanceOf(MessagingException::class.java)

        // Provider failure -> fail closed
        mode.set("ACTIVE_DRIVER")
        val principal2 = connect(interceptor, channel, "sess-drv-11", "valid-driver-token", "DRIVER")
        mode.set("ERROR")
        assertThatThrownBy {
            send(interceptor, channel, "sess-drv-11", principal2, "/app/location", """{"lat":21.0,"lng":105.8}""")
        }.isInstanceOf(MessagingException::class.java)
    }

    @Test
    fun `open STOMP session revalidates Firebase token revocation once interval elapses and blocks revoked session`() {
        val firebaseAuth = Mockito.mock(FirebaseAuth::class.java)
        val token = Mockito.mock(FirebaseToken::class.java)
        Mockito.`when`(token.uid).thenReturn("uid-driver-10")
        Mockito.`when`(firebaseAuth.verifyIdToken("revocable-token", true)).thenReturn(token)

        val userProvider = object : AuthenticatedUserProvider {
            override fun findAuthenticatedUser(firebaseUid: String, roleHint: Role?): AuthUser =
                AuthUser(10L, "uid-driver-10", Role.DRIVER, "Driver 10", null, null, "ONLINE")
        }

        val verifier = FirebaseAuthTokenVerifier(firebaseAuth, userProvider)
        val interceptor = StompAuthInterceptor(verifier, revalidationIntervalMs = 30_000L)
        val start = java.time.Instant.parse("2026-11-23T06:00:00Z")
        interceptor.clock = java.time.Clock.fixed(start, java.time.ZoneOffset.UTC)
        val channel = ExecutorSubscribableChannel()

        val principal = connect(interceptor, channel, "sess-reval", "revocable-token", "DRIVER")

        // Within 30s window: GPS frames do NOT call Firebase remotely
        interceptor.clock = java.time.Clock.fixed(start.plusSeconds(15), java.time.ZoneOffset.UTC)
        send(interceptor, channel, "sess-reval", principal, "/app/location", """{"lat":21.0,"lng":105.8}""")
        Mockito.verify(firebaseAuth, Mockito.times(1)).verifyIdToken("revocable-token", true)

        // Token is revoked in Firebase; after 30s window, remote revalidation catches it and fails closed
        Mockito.`when`(firebaseAuth.verifyIdToken("revocable-token", true))
            .thenThrow(Mockito.mock(FirebaseAuthException::class.java))
        interceptor.clock = java.time.Clock.fixed(start.plusSeconds(31), java.time.ZoneOffset.UTC)

        assertThatThrownBy {
            send(interceptor, channel, "sess-reval", principal, "/app/location", """{"lat":21.0,"lng":105.8}""")
        }.isInstanceOf(MessagingException::class.java)
        Mockito.verify(firebaseAuth, Mockito.times(2)).verifyIdToken("revocable-token", true)
    }

    private fun connect(
        interceptor: StompAuthInterceptor,
        channel: ExecutorSubscribableChannel,
        sessionId: String,
        token: String,
        roleHeader: String,
    ): Principal {
        val accessor = StompHeaderAccessor.create(StompCommand.CONNECT).apply {
            this.sessionId = sessionId
            setNativeHeader("Authorization", "Bearer $token")
            setNativeHeader("X-Role", roleHeader)
            setLeaveMutable(true)
        }
        val msg = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
        val out = interceptor.preSend(msg, channel)!!
        return StompHeaderAccessor.wrap(out).user!!
    }

    private fun send(
        interceptor: StompAuthInterceptor,
        channel: ExecutorSubscribableChannel,
        sessionId: String,
        principal: Principal,
        destination: String,
        payload: String,
    ) {
        val accessor = StompHeaderAccessor.create(StompCommand.SEND).apply {
            this.sessionId = sessionId
            this.user = principal
            this.destination = destination
            setLeaveMutable(true)
        }
        val msg = MessageBuilder.createMessage(payload.toByteArray(StandardCharsets.UTF_8), accessor.messageHeaders)
        interceptor.preSend(msg, channel)
    }

    private fun subscribe(
        interceptor: StompAuthInterceptor,
        channel: ExecutorSubscribableChannel,
        sessionId: String,
        principal: Principal,
        destination: String,
    ) {
        val accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE).apply {
            this.sessionId = sessionId
            this.user = principal
            this.destination = destination
            this.subscriptionId = "sub-1"
            setLeaveMutable(true)
        }
        val msg = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
        interceptor.preSend(msg, channel)
    }
}
