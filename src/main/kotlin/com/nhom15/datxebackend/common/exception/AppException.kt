package com.nhom15.datxebackend.common.exception

import com.nhom15.datxebackend.common.api.ErrorCode
import org.springframework.http.HttpStatus

open class AppException(
    val errorCode: ErrorCode,
    message: String = errorCode.defaultMessage,
    val httpStatus: HttpStatus = errorCode.httpStatus,
    cause: Throwable? = null,
    val details: Map<String, String>? = null
) : BaseAppException(errorCode.name, message, cause)
