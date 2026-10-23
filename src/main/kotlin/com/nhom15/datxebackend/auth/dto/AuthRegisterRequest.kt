package com.nhom15.datxebackend.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AuthRegisterRequest(
    @field:NotBlank(message = "Firebase ID token không được để trống")
    val idToken: String,

    @field:NotBlank(message = "Họ và tên không được để trống")
    @field:Size(max = 120, message = "Họ và tên không được dài quá 120 ký tự")
    val fullName: String,

    @field:Size(max = 32, message = "Số điện thoại không hợp lệ")
    val phoneNumber: String? = null,
)
