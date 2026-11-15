package com.nhom15.datxebackend.ekyc

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

@RestController
@RequestMapping("/api/driver/ekyc")
@ConditionalOnProperty(prefix = "app.ekyc", name = ["enabled"], havingValue = "true")
class EkycController(private val client: EkycClient) {
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
        return ApiResponse.ok(client.extract(documentSlot, mediaType, request.inputStream))
    }
}
