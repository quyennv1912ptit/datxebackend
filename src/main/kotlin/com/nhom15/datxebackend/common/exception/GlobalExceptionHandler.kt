package com.nhom15.datxebackend.common.exception

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.ErrorCode
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(AppException::class)
    fun handleApp(e: AppException): ResponseEntity<ApiResponse<Nothing>> {
        if (e.httpStatus.is5xxServerError) log.error("AppException: {}", e.message, e)
        else log.warn("AppException [{}]: {}", e.errorCode, e.message)
        return respond(e.httpStatus, e.errorCode, e.message ?: e.errorCode.defaultMessage)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleBodyValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiResponse<Nothing>> {
        val details = e.bindingResult.fieldErrors
            .associate { it.field to (it.defaultMessage ?: "Không hợp lệ") }
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, details = details)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraint(e: ConstraintViolationException): ResponseEntity<ApiResponse<Nothing>> {
        val details = e.constraintViolations
            .associate { it.propertyPath.toString() to it.message }
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, details = details)
    }

    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
        IllegalArgumentException::class
    )
    fun handleMalformed(e: Exception): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("Bad request: {}", e.message)
        return respond(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Yêu cầu không hợp lệ hoặc sai định dạng")
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<ApiResponse<Nothing>> =
        respond(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN)

    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleOptimisticLock(e: ObjectOptimisticLockingFailureException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("Optimistic lock conflict: {}", e.message)
        return respond(HttpStatus.CONFLICT, ErrorCode.CONFLICT)
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataConflict(e: DataIntegrityViolationException): ResponseEntity<ApiResponse<Nothing>> {
        log.warn("Data integrity conflict: {}", e.mostSpecificCause.message)
        return respond(HttpStatus.CONFLICT, ErrorCode.CONFLICT)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ApiResponse<Nothing>> {
        if (e is ErrorResponse) return handleSpringError(e)

        log.error("Unhandled exception", e)
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR)
    }

    private fun handleSpringError(e: ErrorResponse): ResponseEntity<ApiResponse<Nothing>> {
        val status = e.statusCode
        val code = when (status.value()) {
            400 -> ErrorCode.BAD_REQUEST
            401 -> ErrorCode.UNAUTHORIZED
            403 -> ErrorCode.FORBIDDEN
            404 -> ErrorCode.NOT_FOUND
            409 -> ErrorCode.CONFLICT
            else -> if (status.is4xxClientError) ErrorCode.BAD_REQUEST else ErrorCode.INTERNAL_ERROR
        }
        return ResponseEntity.status(status)
            .body(ApiResponse.fail(code, if (status.is5xxServerError) code.defaultMessage else e.body.detail ?: code.defaultMessage))
    }

    private fun respond(
        status: HttpStatus,
        code: ErrorCode,
        message: String = code.defaultMessage,
        details: Map<String, String>? = null
    ): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(status).body(ApiResponse.fail(code, message, details))
}
