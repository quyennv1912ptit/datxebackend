package com.nhom15.datxebackend.ekyc

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.http.HttpTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

internal const val MAX_IMAGE_BYTES = 8_000_000
private const val MAX_RESPONSE_BYTES = 256_000
private const val EXTRACTION_CACHE_TTL_MS = 15 * 60 * 1000L
private const val EXTRACTION_CACHE_MAX_ENTRIES = 256

enum class EkycDocumentSlot(val wireName: String) {
    CCCD_FRONT("cccd_front"),
    CCCD_BACK("cccd_back"),
    GPLX_FRONT("gplx_front"),
    GPLX_BACK("gplx_back"),
}

class EkycClient(
    private val upstream: RestClient,
    private val mapper: ObjectMapper,
    private val maxRetries: Int = 1,
    val faceComparisonEnabled: Boolean = false,
) {
    private val permits = Semaphore(2)
    private val extractionCache = ConcurrentHashMap<String, CachedExtraction>()

    private data class CachedExtraction(val data: JsonNode, val expiresAtMs: Long)

    fun extract(slot: EkycDocumentSlot, mediaType: MediaType, input: InputStream): JsonNode {
        val image = try {
            input.readNBytes(MAX_IMAGE_BYTES + 1)
        } catch (_: IOException) {
            throw AppException(ErrorCode.EKYC_INVALID_IMAGE)
        }
        if (image.size > MAX_IMAGE_BYTES) throw AppException(ErrorCode.EKYC_IMAGE_TOO_LARGE)
        if (image.isEmpty()) throw AppException(ErrorCode.EKYC_INVALID_IMAGE)

        val digest = sha256Hex(image)
        val cacheKey = "${slot.wireName}:$digest"
        val now = System.currentTimeMillis()
        extractionCache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) {
                return cached.data
            }
            extractionCache.remove(cacheKey, cached)
        }

        if (!permits.tryAcquire()) throw AppException(ErrorCode.EKYC_BUSY)
        try {
            val result = executeWithRetry {
                upstream.post().uri("/ekyc/documents/{slot}/extract", slot.wireName)
                    .contentType(mediaType).body(image)
                    .exchange { _, response ->
                        val status = response.statusCode
                        if (status.is3xxRedirection || status.value() in setOf(401, 403)) {
                            throw AppException(ErrorCode.EKYC_UNAVAILABLE)
                        }
                        if (status.value() == 504) throw AppException(ErrorCode.EKYC_TIMEOUT)
                        if (status.value() in setOf(502, 503)) throw RetryableUpstreamException(ErrorCode.EKYC_BUSY)
                        if (status.value() == 429) throw AppException(ErrorCode.EKYC_BUSY)
                        val body = response.body.readNBytes(MAX_RESPONSE_BYTES + 1)
                        if (body.size > MAX_RESPONSE_BYTES) throw AppException(ErrorCode.EKYC_UNAVAILABLE)
                        val payload = mapper.readTree(body)
                        if (!status.is2xxSuccessful || !payload.path("success").asBoolean()) {
                            val code = when (payload.path("errorCode").asString("")) {
                                "ENGINE_TIMEOUT" -> ErrorCode.EKYC_TIMEOUT
                                "ENGINE_BUSY" -> ErrorCode.EKYC_BUSY
                                "INVALID_IMAGE", "INVALID_REQUEST" -> ErrorCode.EKYC_INVALID_IMAGE
                                "IMAGE_TOO_LARGE" -> ErrorCode.EKYC_IMAGE_TOO_LARGE
                                else -> ErrorCode.EKYC_UNAVAILABLE
                            }
                            if (code == ErrorCode.EKYC_BUSY) throw RetryableUpstreamException(code)
                            throw AppException(code)
                        }
                        val data = payload.path("data")
                        if (data.path("slot").asString("") != slot.wireName || !data.path("candidates").isArray) {
                            throw AppException(ErrorCode.EKYC_UNAVAILABLE)
                        }
                        data
                    } ?: throw AppException(ErrorCode.EKYC_UNAVAILABLE)
            }
            cacheExtraction(cacheKey, result, now)
            return result
        } finally {
            permits.release()
        }
    }

    fun evaluateFace(images: Map<String, ByteArray>, qualityOnly: Boolean = true): JsonNode {
        if (!faceComparisonEnabled || images.isEmpty()) {
            return disabledFaceResult(if (!faceComparisonEnabled) "DISABLED" else "MISSING_IMAGE")
        }
        val encoded = mutableMapOf<String, String>()
        val encoder = Base64.getEncoder()
        for ((slot, bytes) in images) {
            if (bytes.isEmpty()) throw AppException(ErrorCode.EKYC_INVALID_IMAGE)
            if (bytes.size > MAX_IMAGE_BYTES) throw AppException(ErrorCode.EKYC_IMAGE_TOO_LARGE)
            encoded[slot] = encoder.encodeToString(bytes)
        }
        if (!permits.tryAcquire()) throw AppException(ErrorCode.EKYC_BUSY)
        try {
            val requestBody = mapper.writeValueAsBytes(
                mapOf("quality_only" to qualityOnly, "images" to encoded),
            )
            return executeWithRetry {
                upstream.post().uri("/ekyc/face/evaluate")
                    .contentType(MediaType.APPLICATION_JSON).body(requestBody)
                    .exchange { _, response ->
                        val status = response.statusCode
                        if (status.is3xxRedirection || status.value() in setOf(401, 403)) {
                            throw AppException(ErrorCode.EKYC_UNAVAILABLE)
                        }
                        if (status.value() == 504) throw AppException(ErrorCode.EKYC_TIMEOUT)
                        if (status.value() in setOf(502, 503)) throw RetryableUpstreamException(ErrorCode.EKYC_BUSY)
                        if (status.value() == 429) throw AppException(ErrorCode.EKYC_BUSY)
                        val body = response.body.readNBytes(MAX_RESPONSE_BYTES + 1)
                        if (body.size > MAX_RESPONSE_BYTES) throw AppException(ErrorCode.EKYC_UNAVAILABLE)
                        val payload = mapper.readTree(body)
                        if (!status.is2xxSuccessful || !payload.path("success").asBoolean()) {
                            val code = when (payload.path("errorCode").asString("")) {
                                "ENGINE_TIMEOUT" -> ErrorCode.EKYC_TIMEOUT
                                "ENGINE_BUSY" -> ErrorCode.EKYC_BUSY
                                "INVALID_IMAGE", "INVALID_REQUEST" -> ErrorCode.EKYC_INVALID_IMAGE
                                "IMAGE_TOO_LARGE" -> ErrorCode.EKYC_IMAGE_TOO_LARGE
                                else -> ErrorCode.EKYC_UNAVAILABLE
                            }
                            if (code == ErrorCode.EKYC_BUSY) throw RetryableUpstreamException(code)
                            throw AppException(code)
                        }
                        val data = payload.path("data")
                        if (!data.isObject) throw AppException(ErrorCode.EKYC_UNAVAILABLE)
                        data
                    } ?: throw AppException(ErrorCode.EKYC_UNAVAILABLE)
            }
        } finally {
            permits.release()
        }
    }

    fun disabledFaceResult(reason: String = "DISABLED"): JsonNode {
        val node = mapper.createObjectNode()
        node.put("mode", "NOT_EVALUATED")
        node.put("reason", reason)
        node.put("liveness", "NOT_EVALUATED")
        node.set("quality", mapper.createObjectNode())
        val pairs = mapper.createArrayNode()
        for ((left, right) in listOf(
            "cccd_front" to "selfie",
            "gplx_front" to "selfie",
            "cccd_front" to "gplx_front",
        )) {
            val pair = mapper.createObjectNode()
            pair.put("left", left)
            pair.put("right", right)
            pair.put("status", "NOT_EVALUATED")
            pairs.add(pair)
        }
        node.set("pairs", pairs)
        return node
    }

    private class RetryableUpstreamException(val code: ErrorCode) : RuntimeException()

    private fun <T> executeWithRetry(call: () -> T): T {
        val attempts = maxRetries.coerceIn(0, 2) + 1
        var lastError: AppException? = null
        for (attempt in 1..attempts) {
            try {
                return call()
            } catch (ex: RetryableUpstreamException) {
                lastError = AppException(ex.code)
            } catch (error: ResourceAccessException) {
                val timedOut = generateSequence<Throwable>(error) { it.cause }
                    .any { it is HttpTimeoutException || it is SocketTimeoutException }
                if (timedOut) throw AppException(ErrorCode.EKYC_TIMEOUT)
                lastError = AppException(ErrorCode.EKYC_UNAVAILABLE)
            } catch (_: JacksonException) {
                throw AppException(ErrorCode.EKYC_UNAVAILABLE)
            } catch (_: RestClientException) {
                lastError = AppException(ErrorCode.EKYC_UNAVAILABLE)
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(250L * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw lastError ?: AppException(ErrorCode.EKYC_UNAVAILABLE)
    }

    private fun cacheExtraction(key: String, data: JsonNode, now: Long) {
        if (extractionCache.size >= EXTRACTION_CACHE_MAX_ENTRIES) {
            extractionCache.entries.removeIf { it.value.expiresAtMs <= now }
            if (extractionCache.size >= EXTRACTION_CACHE_MAX_ENTRIES) {
                extractionCache.keys.take(32).forEach { extractionCache.remove(it) }
            }
        }
        extractionCache[key] = CachedExtraction(data, now + EXTRACTION_CACHE_TTL_MS)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
