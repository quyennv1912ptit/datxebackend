package com.nhom15.datxebackend.notification.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.nhom15.datxebackend.common.websocket.PayloadType

data class DriverAccountNotificationPayload(
    @JsonIgnore override val type: PayloadType,
    val status: String,
    @JsonProperty("rejection_reason") val rejectionReason: String? = null,
    val reason: String? = null,
) : NotificationPayload {
    override val title: String
        get() = when (type) {
            PayloadType.DRIVER_VERIFICATION_UPDATED -> "Cập nhật trạng thái xác minh tài xế"
            PayloadType.DRIVER_FORCE_OFFLINE -> "Tài khoản đã được chuyển về ngoại tuyến"
            else -> type.name
        }

    override val body: String
        get() = when (type) {
            PayloadType.DRIVER_VERIFICATION_UPDATED -> when (status) {
                "APPROVED" -> "Hồ sơ tài xế đã được duyệt."
                "PENDING" -> "Hồ sơ tài xế cần bổ sung${rejectionReason?.let { ": $it" }.orEmpty()}"
                else -> "Hồ sơ tài xế chưa được duyệt${rejectionReason?.let { ": $it" }.orEmpty()}"
            }
            PayloadType.DRIVER_FORCE_OFFLINE -> reason?.let { "Lý do: $it" }
                ?: "Quản trị viên đã đưa tài khoản của bạn về ngoại tuyến."
            else -> type.name
        }
}
