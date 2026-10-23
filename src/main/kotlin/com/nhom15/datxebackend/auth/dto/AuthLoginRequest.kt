package com.nhom15.datxebackend.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AuthLoginRequest(
    @field:NotBlank(message = "Firebase ID token không được để trống")
    val idToken: String,

    @field:Size(max = 32, message = "Số điện thoại không hợp lệ")
    val phoneNumber: String? = null,
)
