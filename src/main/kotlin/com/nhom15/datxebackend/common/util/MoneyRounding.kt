package com.nhom15.datxebackend.common.util

import java.math.BigDecimal
import java.math.RoundingMode

private val ONE_THOUSAND_VND = BigDecimal.valueOf(1_000)

/**
 * Làm tròn số tiền VND về bội số 1.000 gần nhất theo HALF_UP.
 *
 * Ví dụ: 31.499 -> 31.000; 31.500 -> 32.000; 31.250 -> 31.000.
 * Dùng BigDecimal để tránh sai số floating-point khi tính tiền.
 *
 * Chỉ dùng cho số tiền khách thực trả. Chiết khấu tài xế vẫn tính đến 1 VND
 * (xem WalletService.commission), không dùng hàm này.
 */
fun Long.roundToNearestThousandVnd(): Long {
    require(this >= 0) { "Số tiền không được âm" }
    return BigDecimal.valueOf(this)
        .divide(ONE_THOUSAND_VND)
        .setScale(0, RoundingMode.HALF_UP)
        .multiply(ONE_THOUSAND_VND)
        .longValueExact()
}
