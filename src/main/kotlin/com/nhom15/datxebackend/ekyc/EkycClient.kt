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
import java.util.concurrent.Semaphore

internal const val MAX_IMAGE_BYTES = 8_000_000
private const val MAX_RESPONSE_BYTES = 256_000

enum class EkycDocumentSlot(val wireName: String) {
    CCCD_FRONT("cccd_front"),
    CCCD_BACK("cccd_back"),
    GPLX_FRONT("gplx_front"),
    GPLX_BACK("gplx_back"),
}

class EkycClient(private val upstream: RestClient, private val mapper: ObjectMapper) {
    private val permits = Semaphore(2)

    fun extract(slot: EkycDocumentSlot, mediaType: MediaType, input: InputStream): JsonNode {
        if (!permits.tryAcquire()) throw AppException(ErrorCode.EKYC_BUSY)
        try {
            val image = try {
                input.readNBytes(MAX_IMAGE_BYTES + 1)
            } catch (_: IOException) {
                throw AppException(ErrorCode.EKYC_INVALID_IMAGE)
            }
            if (image.size > MAX_IMAGE_BYTES) throw AppException(ErrorCode.EKYC_IMAGE_TOO_LARGE)
            if (image.isEmpty()) throw AppException(ErrorCode.EKYC_INVALID_IMAGE)
            return upstream.post().uri("/ekyc/documents/{slot}/extract", slot.wireName)
                .contentType(mediaType).body(image)
                .exchange { _, response ->
                    val status = response.statusCode
                    if (status.is3xxRedirection || status.value() in setOf(401, 403)) {
                        throw AppException(ErrorCode.EKYC_UNAVAILABLE)
                    }
                    if (status.value() == 504) throw AppException(ErrorCode.EKYC_TIMEOUT)
                    val body = response.body.readNBytes(MAX_RESPONSE_BYTES + 1)
                    if (body.size > MAX_RESPONSE_BYTES) throw AppException(ErrorCode.EKYC_UNAVAILABLE)
                    val payload = mapper.readTree(body)
                    if (!status.is2xxSuccessful || !payload.path("success").asBoolean()) {
                        throw AppException(
                            when (payload.path("errorCode").asString("")) {
                                "ENGINE_TIMEOUT" -> ErrorCode.EKYC_TIMEOUT
                                "ENGINE_BUSY" -> ErrorCode.EKYC_BUSY
                                "INVALID_IMAGE", "INVALID_REQUEST" ->
                                    ErrorCode.EKYC_INVALID_IMAGE
                                "IMAGE_TOO_LARGE" -> ErrorCode.EKYC_IMAGE_TOO_LARGE
                                else -> ErrorCode.EKYC_UNAVAILABLE
                            },
                        )
                    }
                    val data = payload.path("data")
                    if (data.path("slot").asString("") != slot.wireName || !data.path("candidates").isArray) {
                        throw AppException(ErrorCode.EKYC_UNAVAILABLE)
                    }
                    data
                } ?: throw AppException(ErrorCode.EKYC_UNAVAILABLE)
        } catch (error: ResourceAccessException) {
            val timedOut = generateSequence<Throwable>(error) { it.cause }
                .any { it is HttpTimeoutException || it is SocketTimeoutException }
            throw AppException(if (timedOut) ErrorCode.EKYC_TIMEOUT else ErrorCode.EKYC_UNAVAILABLE)
        } catch (_: JacksonException) {
            throw AppException(ErrorCode.EKYC_UNAVAILABLE)
        } catch (_: RestClientException) {
            throw AppException(ErrorCode.EKYC_UNAVAILABLE)
        } finally {
            permits.release()
        }
    }
}
