package com.nhom15.datxebackend.common.api

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiResponse<out T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null,
    val timestamp: String? = null,
    val status: Int? = null,
    @get:JsonProperty("error_code") val errorCode: String? = null,
    val message: String? = null
) {
    companion object {
        fun <T> ok(data: T): ApiResponse<T> = ApiResponse(success = true, data = data)

        fun ok(): ApiResponse<Unit> = ApiResponse(success = true, data = Unit)

        fun fail(
            code: ErrorCode,
            message: String = code.defaultMessage,
            details: Map<String, String>? = null,
            status: Int = code.httpStatus.value()
        ): ApiResponse<Nothing> =
            ApiResponse(success = false, error = ApiError(code.name, message, details),
                timestamp = java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toString(),
                status = status, errorCode = code.name, message = message)
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ApiError(
    val code: String,
    val message: String,
    val details: Map<String, String>? = null
)
