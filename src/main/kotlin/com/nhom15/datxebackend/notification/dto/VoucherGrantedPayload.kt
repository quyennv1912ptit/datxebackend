package com.nhom15.datxebackend.notification.dto

import com.nhom15.datxebackend.common.websocket.PayloadType

data class VoucherGrantedPayload(val rewardKind: String) : NotificationPayload {
    override val type: PayloadType get() = PayloadType.VOUCHER_GRANTED
    override val title: String get() = "Bạn vừa nhận được voucher"
    override val body: String get() = if (rewardKind == "WELCOME") "Voucher chào mừng đã được thêm vào Ưu đãi của tôi." else "Bạn vừa mở khóa voucher mốc chuyến đi."
}
