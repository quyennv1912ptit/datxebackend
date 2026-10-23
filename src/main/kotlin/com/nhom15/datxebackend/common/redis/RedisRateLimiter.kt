package com.nhom15.datxebackend.common.redis

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class RedisRateLimiter(private val redis: StringRedisTemplate) {
    fun check(key: String, limit: Long, window: Duration) {
        val count = redis.execute(
            INCREMENT_WITH_EXPIRY,
            listOf(key),
            window.seconds.coerceAtLeast(1).toString(),
        ) ?: 1L
        if (count > limit) throw AppException(ErrorCode.RATE_LIMITED)
    }

    private companion object {
        val INCREMENT_WITH_EXPIRY = DefaultRedisScript<Long>(
            """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """.trimIndent(),
            Long::class.java,
        )
    }
}
