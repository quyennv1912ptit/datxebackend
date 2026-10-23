package com.nhom15.datxebackend.chat.service

import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import com.nhom15.datxebackend.common.security.AuthUser
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class ChatRateLimiter(private val rateLimiter: RedisRateLimiter) {
    fun check(actor: AuthUser, limit: Long = 20, window: Duration = Duration.ofMinutes(1)) {
        val key = RedisKeys.chatRate(actor.role, actor.id)
        rateLimiter.check(key, limit, window)
    }
}
