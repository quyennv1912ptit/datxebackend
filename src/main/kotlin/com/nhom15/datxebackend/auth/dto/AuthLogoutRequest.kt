package com.nhom15.datxebackend.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class AuthLogoutRequest(
    @field:NotBlank(message = "Installation ID không được để trống")
    @field:Pattern(
        regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        message = "Installation ID phải có định dạng UUID",
    )
    val installationId: String,
)
