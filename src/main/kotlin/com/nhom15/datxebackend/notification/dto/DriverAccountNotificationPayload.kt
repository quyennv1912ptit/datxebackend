package com.nhom15.datxebackend.notification.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.nhom15.datxebackend.common.websocket.PayloadType

data class DriverAccountNotificationPayload(
    @JsonIgnore override val type: PayloadType,
    val status: String,
    @JsonProperty("rejection_reason") val rejectionReason: String? = null,
    val reason: String? = null,
    @JsonProperty("account_status") val accountStatus: String? = null,
    @JsonProperty("withdrawal_frozen") val withdrawalFrozen: Boolean? = null,
) : NotificationPayload {
    override val title: String
        get() = when (type) {
            PayloadType.DRIVER_VERIFICATION_UPDATED -> "Cập nhật trạng thái xác minh tài xế"
            PayloadType.DRIVER_FORCE_OFFLINE -> "Tài khoản đã được chuyển về ngoại tuyến"
            PayloadType.DRIVER_ACCOUNT_STATUS_UPDATED -> when (accountStatus) {
                "BANNED" -> "Tài khoản tài xế đã bị khóa"
                "SUSPENDED" -> "Tài khoản đang tạm ngưng nhận chuyến"
                "ACTIVE" -> "Tài khoản tài xế đã được mở khóa"
                else -> "Cập nhật trạng thái tài khoản tài xế"
            }
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
            PayloadType.DRIVER_ACCOUNT_STATUS_UPDATED -> when (accountStatus) {
                "BANNED" -> reason?.let { "Lý do: $it" } ?: "Tài khoản của bạn đã bị khóa vĩnh viễn."
                "SUSPENDED" -> reason?.let { "Lý do: $it" } ?: "Tài khoản của bạn đang bị tạm ngưng nhận chuyến."
                "ACTIVE" -> "Bạn có thể tiếp tục sử dụng tài khoản và bật nhận chuyến."
                else -> reason ?: "Trạng thái tài khoản tài xế của bạn đã được cập nhật."
            }
            else -> type.name
        }
}
