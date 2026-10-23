
package com.nhom15.datxebackend.notification.token

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class DeviceTokenRequest(
    @field:NotBlank(message = "Installation ID không được để trống")
    @field:Pattern(
        regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        message = "Installation ID phải có định dạng UUID"
    )
    val installationId: String,

    @field:NotBlank(message = "FCM token không được để trống")
    @field:Size(max = 255, message = "FCM token không được dài quá 255 ký tự")
    val fcmToken: String,

    @field:NotBlank(message = "Platform không được để trống")
    @field:Pattern(
        regexp = "^(ANDROID|IOS)$",
        message = "Platform chỉ được là ANDROID hoặc IOS"
    )
    val platform: String
)
