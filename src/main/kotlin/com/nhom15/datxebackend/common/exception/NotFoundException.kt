package com.nhom15.datxebackend.common.exception

import com.nhom15.datxebackend.common.api.ErrorCode
import org.springframework.http.HttpStatus

class NotFoundException(
    errorCode: ErrorCode = ErrorCode.NOT_FOUND,
    message: String = errorCode.defaultMessage
) : AppException(errorCode, message, HttpStatus.NOT_FOUND)
