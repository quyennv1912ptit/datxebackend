package com.nhom15.datxebackend.routing.service

import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import com.nhom15.datxebackend.common.security.AuthUser
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class MapboxRateLimiter(private val rateLimiter: RedisRateLimiter) {
    fun check(actor: AuthUser, purpose: String) {
        rateLimiter.check(
            RedisKeys.mapboxRate(purpose, actor.role, actor.id),
            LIMIT_PER_MINUTE,
            WINDOW,
        )
    }

    private companion object {
        const val LIMIT_PER_MINUTE = 60L
        val WINDOW: Duration = Duration.ofMinutes(1)
    }
}
