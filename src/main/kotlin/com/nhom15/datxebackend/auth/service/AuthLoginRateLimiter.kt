package com.nhom15.datxebackend.auth.service

import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class AuthLoginRateLimiter(private val rateLimiter: RedisRateLimiter) {
    fun check(remoteAddress: String) {
        rateLimiter.check(RedisKeys.authLoginRate(remoteAddress), LIMIT_PER_MINUTE, WINDOW)
    }

    private companion object {
        const val LIMIT_PER_MINUTE = 60L
        val WINDOW: Duration = Duration.ofMinutes(1)
    }
}
