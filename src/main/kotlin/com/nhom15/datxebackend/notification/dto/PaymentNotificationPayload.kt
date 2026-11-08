package com.nhom15.datxebackend.notification.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.websocket.PayloadType

data class PaymentNotificationPayload(override val rideId: Long, val fromMethod: PaymentMethod,
    val method: PaymentMethod, val status: String, val amount: Long) : NotificationPayload {
    @get:JsonIgnore override val type = PayloadType.PAYMENT_UPDATED
    override val title: String get() = if (fromMethod != method) "Khách đã đổi phương thức thanh toán" else "Đã xác nhận thanh toán"
    override val body: String get() = if (fromMethod == method)
        "Thanh toán tiền mặt cho chuyến #$rideId đã được tài xế xác nhận."
        else if (method == PaymentMethod.WALLET)
            "Chuyến #$rideId đã thanh toán bằng ví ứng dụng. Tài xế không thu thêm tiền mặt."
        else "Chuyến #$rideId đã đổi sang tiền mặt. Tài xế chỉ xác nhận thanh toán sau khi nhận tiền."
}
