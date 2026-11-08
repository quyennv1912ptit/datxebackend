package com.nhom15.datxebackend.notification.dto

import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.payment.entity.WithdrawalStatus
import com.fasterxml.jackson.annotation.JsonIgnore
import java.text.NumberFormat
import java.util.Locale

data class WithdrawalNotificationPayload(val withdrawalId: Long, val amount: Long,
    val status: WithdrawalStatus, val rejectReason: String?) : NotificationPayload {
    @get:JsonIgnore override val type = PayloadType.WITHDRAWAL_REVIEWED
    override val title: String get() = if (status == WithdrawalStatus.SUCCESS) "Rút tiền thành công" else "Yêu cầu rút tiền bị từ chối"
    override val body: String get() {
        val money = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(amount) + "đ"
        return if (status == WithdrawalStatus.SUCCESS)
            "Yêu cầu rút $money của bạn đã thành công. Vui lòng kiểm tra tài khoản ngân hàng."
        else "Yêu cầu rút $money bị từ chối. Lý do: $rejectReason. Số tiền đã được hoàn lại vào ví."
    }
}
