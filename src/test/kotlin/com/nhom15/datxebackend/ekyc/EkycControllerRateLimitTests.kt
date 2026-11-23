package com.nhom15.datxebackend.ekyc

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import com.nhom15.datxebackend.common.security.AuthUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EkycControllerRateLimitTests {
    private val emptyResult = JsonMapper.builder().build().readTree("{}")!!
    private val extractCalls = mutableListOf<List<Any?>>()
    private val redisCalls = mutableListOf<List<Any?>>()

    private val client = Mockito.mock(EkycClient::class.java, Answer<Any?> { invocation ->
        extractCalls += invocation.arguments.toList()
        if (invocation.method.name == "extract") emptyResult else null
    })
    private val redis = Mockito.mock(RedisRateLimiter::class.java, Answer<Any?> { invocation ->
        val args = invocation.arguments.toList()
        redisCalls += args
        if (args[0] == "ekyc:extract:DRIVER:42:cccd_front") {
            throw AppException(ErrorCode.RATE_LIMITED)
        }
        null
    })

    @BeforeEach
    fun setUp() {
        extractCalls.clear()
        redisCalls.clear()
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            AuthUser(
                id = 42, firebaseUid = "driver-uid", role = Role.DRIVER,
                fullName = null, email = null, phoneNumber = null,
                status = "OFFLINE", verificationStatus = "PENDING",
            ),
            null,
            listOf(SimpleGrantedAuthority("ROLE_DRIVER")),
        )
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `paid extraction quota is keyed by server-side driver profile id and slot`() {
        val controller = EkycController(client, redis)

        val error = assertFailsWith<AppException> {
            controller.extract("cccd_front", request(), MockHttpServletResponse())
        }

        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode)
        assertEquals(1, redisCalls.size)
        assertEquals("ekyc:extract:DRIVER:42:cccd_front", redisCalls.single()[0])
        assertEquals(12L, redisCalls.single()[1])
        assertEquals(Duration.ofMinutes(1), redisCalls.single()[2])
        assertTrue(extractCalls.isEmpty())
    }

    @Test
    fun `local fast fail still caps repeated extraction attempts`() {
        val controller = EkycController(client)
        repeat(12) { controller.extract("cccd_front", request(), MockHttpServletResponse()) }

        val error = assertFailsWith<AppException> {
            controller.extract("cccd_front", request(), MockHttpServletResponse())
        }

        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode)
        assertTrue(redisCalls.isEmpty())
    }

    @Test
    fun `redis outage falls back to the local quota instead of failing extraction`() {
        val failingRedis = Mockito.mock(RedisRateLimiter::class.java) { throw IllegalStateException("Redis down") }
        val controller = EkycController(client, failingRedis)

        repeat(12) { controller.extract("cccd_front", request(), MockHttpServletResponse()) }

        assertEquals(12, extractCalls.size)
        val error = assertFailsWith<AppException> {
            controller.extract("cccd_front", request(), MockHttpServletResponse())
        }
        assertEquals(ErrorCode.RATE_LIMITED, error.errorCode)
        assertEquals(12, extractCalls.size)
    }

    private fun request(): MockHttpServletRequest = MockHttpServletRequest().apply {
        contentType = "image/jpeg"
        setContent(byteArrayOf(1, 2, 3))
        remoteAddr = "10.0.0.5"
    }
}
