package com.nhom15.datxebackend.auth.service

import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

@Service
class AuthLoginRateLimiter(private val rateLimiter: RedisRateLimiter) {
    /**
     * @param endpoint separates login/register/admin buckets so one shared proxy IP cannot
     *        exhaust every auth endpoint at once.
     * @param remoteAddress should already be resolved by the trusted edge proxy. This method
     *        only normalizes the string and is NOT an IP validator: a proxy that forwards a
     *        client-supplied X-Forwarded-* value into this parameter can still split the IP
     *        bucket. The edge must strip untrusted forwarding headers.
     * @param account stable account identifier (verified phone); hashed so Redis keys never
     *        contain personal data.
     */
    fun check(endpoint: String, remoteAddress: String?, account: String? = null) {
        val ip = normalizeIp(remoteAddress)
        rateLimiter.check(
            RedisKeys.authLoginRate("$endpoint:ip:$ip"),
            LIMIT_PER_MINUTE,
            WINDOW,
        )
        val accountHash = hashAccount(account)
        if (accountHash != null) {
            rateLimiter.check(
                RedisKeys.authLoginRate("$endpoint:account:$accountHash"),
                ACCOUNT_LIMIT_PER_MINUTE,
                WINDOW,
            )
        }
    }

    private fun normalizeIp(value: String?): String {
        val candidate = value?.trim()?.substringBefore(',')?.trim().orEmpty()
        if (candidate.isEmpty() || candidate.length > 45) return "unknown"
        return if (candidate.all { it.isDigit() || it == '.' || it == ':' || it == '%' || it in 'a'..'f' || it in 'A'..'F' }) {
            candidate.lowercase()
        } else {
            "unknown"
        }
    }

    private fun hashAccount(account: String?): String? {
        val value = account?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(32)
    }

    private companion object {
        const val LIMIT_PER_MINUTE = 60L
        const val ACCOUNT_LIMIT_PER_MINUTE = 10L
        val WINDOW: Duration = Duration.ofMinutes(1)
    }
}
