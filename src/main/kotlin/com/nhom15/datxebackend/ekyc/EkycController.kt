package com.nhom15.datxebackend.ekyc

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import com.nhom15.datxebackend.common.security.AuthUser
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

private const val EXTRACT_WINDOW_MS = 60_000L
private const val EXTRACT_MAX_PER_WINDOW = 12L
private const val EXTRACT_KEY_PREFIX = "ekyc:extract:"

@RestController
@RequestMapping("/api/driver/ekyc")
@ConditionalOnProperty(prefix = "app.ekyc", name = ["enabled"], havingValue = "true")
class EkycController(
    private val client: EkycClient,
    // Optional only so standalone tests can construct the controller without Redis.
    private val rateLimiter: RedisRateLimiter? = null,
) {
    private val localExtractAttempts = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/documents/{slot}/extract", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun extract(
        @PathVariable slot: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ApiResponse<JsonNode> {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        val documentSlot = EkycDocumentSlot.entries.firstOrNull { it.wireName == slot }
            ?: throw AppException(ErrorCode.BAD_REQUEST, "Mặt giấy tờ không được hỗ trợ")
        if (request.contentLengthLong > MAX_IMAGE_BYTES) {
            throw AppException(ErrorCode.EKYC_IMAGE_TOO_LARGE)
        }
        val mediaType = when (request.contentType?.lowercase()) {
            "image/jpeg" -> MediaType.IMAGE_JPEG
            "image/png" -> MediaType.IMAGE_PNG
            "image/webp" -> MediaType.parseMediaType("image/webp")
            else -> throw AppException(ErrorCode.EKYC_INVALID_IMAGE)
        }
        val actor = SecurityContextHolder.getContext().authentication?.principal as? AuthUser
        checkRateLimit(rateKey(actor, documentSlot, request))
        return ApiResponse.ok(client.extract(documentSlot, mediaType, request.inputStream))
    }

    /** Server-owned identity + document slot; never key the paid OCR quota by a client-supplied UID. */
    private fun rateKey(actor: AuthUser?, slot: EkycDocumentSlot, request: HttpServletRequest): String = buildString {
        append(EXTRACT_KEY_PREFIX)
        if (actor != null) {
            append(actor.role.name).append(':').append(actor.id)
        } else {
            append("ip:").append(request.remoteAddr ?: "anonymous")
        }
        append(':').append(slot.wireName)
    }

    private fun checkRateLimit(key: String) {
        // Short local fast-fail sheds obviously abusive bursts before the Redis round-trip.
        // Redis remains authoritative, so the cap is shared by every JVM replica.
        val now = System.currentTimeMillis()
        val window = localExtractAttempts.computeIfAbsent(key) { ConcurrentLinkedDeque() }
        synchronized(window) {
            while (true) {
                val head = window.peekFirst() ?: break
                if (now - head >= EXTRACT_WINDOW_MS) window.pollFirst() else break
            }
            if (window.size >= EXTRACT_MAX_PER_WINDOW) {
                throw AppException(
                    ErrorCode.RATE_LIMITED,
                    "Bạn đã đọc giấy tờ nhiều lần trong thời gian ngắn. Hãy đợi một lát rồi thử lại.",
                )
            }
            window.addLast(now)
        }
        // The local window above is the baseline. A Redis outage must not turn every OCR
        // call into a 500: keep serving under the per-JVM cap and record the degradation.
        try {
            rateLimiter?.let { it.check(key, EXTRACT_MAX_PER_WINDOW, Duration.ofMillis(EXTRACT_WINDOW_MS)) }
        } catch (error: AppException) {
            // A quota decision from Redis is authoritative; only infrastructure failures degrade.
            throw error
        } catch (error: Exception) {
            log.warn("Redis rate limiter unavailable for eKYC extract; using local cap only", error)
        }
    }
}
