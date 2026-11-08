package com.nhom15.datxebackend.common.exception

abstract class BaseAppException(val code: String, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
