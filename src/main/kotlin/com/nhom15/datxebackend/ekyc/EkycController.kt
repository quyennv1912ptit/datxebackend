package com.nhom15.datxebackend.ekyc

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
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
import tools.jackson.databind.JsonNode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

private const val EXTRACT_WINDOW_MS = 60_000L
private const val EXTRACT_MAX_PER_WINDOW = 12

@RestController
@RequestMapping("/api/driver/ekyc")
@ConditionalOnProperty(prefix = "app.ekyc", name = ["enabled"], havingValue = "true")
class EkycController(private val client: EkycClient) {
    private val extractAttempts = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()

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
        checkRateLimit(actor?.firebaseUid ?: request.remoteAddr ?: "anonymous")
        return ApiResponse.ok(client.extract(documentSlot, mediaType, request.inputStream))
    }

    private fun checkRateLimit(actorKey: String) {
        val now = System.currentTimeMillis()
        val window = extractAttempts.computeIfAbsent(actorKey) { ConcurrentLinkedDeque() }
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
    }
}
