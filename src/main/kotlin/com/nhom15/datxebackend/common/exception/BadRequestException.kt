package com.nhom15.datxebackend.common.exception

import com.nhom15.datxebackend.common.api.ErrorCode
import org.springframework.http.HttpStatus

class BadRequestException(
    errorCode: ErrorCode = ErrorCode.BAD_REQUEST,
    message: String = errorCode.defaultMessage
) : AppException(errorCode, message, HttpStatus.BAD_REQUEST)
