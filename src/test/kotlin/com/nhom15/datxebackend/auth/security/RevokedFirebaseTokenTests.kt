package com.nhom15.datxebackend.auth.security

import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.nhom15.datxebackend.auth.service.AuthService
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthenticatedUserProvider
import com.nhom15.datxebackend.notification.ws.FirebaseAuthTokenVerifier
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevokedFirebaseTokenTests {
    private val revoked = FirebaseAuthException(
        com.google.firebase.ErrorCode.INVALID_ARGUMENT,
        "ID token has been revoked",
        null,
        null,
        AuthErrorCode.REVOKED_ID_TOKEN,
    )
    private val verifyCalls = mutableListOf<List<Any?>>()
    private val providerCalls = mutableListOf<List<Any?>>()

    private val firebaseAuth = Mockito.mock(FirebaseAuth::class.java, Answer<Any?> { invocation ->
        verifyCalls += invocation.arguments.toList()
        if (invocation.method.name == "verifyIdToken" && invocation.arguments[0] == "revoked-token") {
            throw revoked
        }
        null
    })
    private val provider = Mockito.mock(AuthenticatedUserProvider::class.java, Answer<Any?> { invocation ->
        providerCalls += invocation.arguments.toList()
        null
    })

    @BeforeEach
    fun setUp() {
        verifyCalls.clear()
        providerCalls.clear()
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `HTTP filter verifies revocation and clears the security context`() {
        val filter = FirebaseAuthFilter(firebaseAuth, Mockito.mock(AuthService::class.java))
        val request = Mockito.mock(HttpServletRequest::class.java, Answer<Any?> { invocation ->
            when (invocation.method.name) {
                "getHeader" -> if (invocation.arguments[0] == "Authorization") "Bearer revoked-token" else null
                "getRequestURI" -> "/api/customer/profile"
                else -> null
            }
        })

        filter.doFilter(request, MockHttpServletResponse(), FilterChain { _, _ -> Unit })

        assertNull(SecurityContextHolder.getContext().authentication)
        assertEquals(1, verifyCalls.size)
        assertEquals("revoked-token", verifyCalls.single()[0])
        assertEquals(true, verifyCalls.single()[1])
    }

    @Test
    fun `STOMP CONNECT rejects a revoked token`() {
        val verifier = FirebaseAuthTokenVerifier(firebaseAuth, provider)

        assertNull(verifier.verify("revoked-token", Role.DRIVER))

        assertEquals(1, verifyCalls.size)
        assertEquals(true, verifyCalls.single()[1])
        assertTrue(providerCalls.isEmpty())
    }
}
