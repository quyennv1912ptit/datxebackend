package com.nhom15.datxebackend.common.exception

import com.nhom15.datxebackend.common.api.ErrorCode
import org.springframework.http.HttpStatus

class ForbiddenException(
    errorCode: ErrorCode = ErrorCode.FORBIDDEN,
    message: String = errorCode.defaultMessage
) : AppException(errorCode, message, HttpStatus.FORBIDDEN)
