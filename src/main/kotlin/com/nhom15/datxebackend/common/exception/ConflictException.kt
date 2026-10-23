package com.nhom15.datxebackend.common.exception

import com.nhom15.datxebackend.common.api.ErrorCode
import org.springframework.http.HttpStatus

class ConflictException(
    errorCode: ErrorCode = ErrorCode.CONFLICT,
    message: String = errorCode.defaultMessage
) : AppException(errorCode, message, HttpStatus.CONFLICT)
