package com.nhom15.datxebackend.auth.service

import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthLoginRateLimiterTests {
    private val calls = mutableListOf<List<Any?>>()
    private val redis = Mockito.mock(RedisRateLimiter::class.java, Answer<Any?> { invocation ->
        calls += invocation.arguments.toList()
        null
    })
    private val limiter = AuthLoginRateLimiter(redis)

    @BeforeEach
    fun setUp() = calls.clear()

    @Test
    fun `auth buckets are separated by endpoint, validated IP and hashed account`() {
        limiter.check("customer-login", "1.2.3.4", "+84912345678")
        limiter.check("admin-login", "1.2.3.4", null)

        assertEquals(3, calls.size)
        val ipKey = calls[0][0] as String
        val accountKey = calls[1][0] as String
        val adminKey = calls[2][0] as String
        assertTrue(ipKey.startsWith("auth:login:rate:customer-login:ip:1.2.3.4"))
        assertTrue(accountKey.startsWith("auth:login:rate:customer-login:account:"))
        assertFalse(accountKey.contains("84912345678"))
        assertTrue(adminKey.startsWith("auth:login:rate:admin-login:ip:1.2.3.4"))
        assertEquals(60L, calls[0][1])
        assertEquals(Duration.ofMinutes(1), calls[0][2])
    }

    @Test
    fun `malformed forwarded address is never used as a bucket key`() {
        limiter.check("customer-login", "not-an-ip, 1.2.3.4")

        assertEquals(1, calls.size)
        assertTrue((calls.single()[0] as String).startsWith("auth:login:rate:customer-login:ip:unknown"))
    }
}
