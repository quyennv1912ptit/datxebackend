package com.nhom15.datxebackend.common.util

import java.math.BigDecimal
import java.math.RoundingMode

private val ONE_THOUSAND_VND = BigDecimal.valueOf(1_000)

/**
 * Làm tròn số tiền VND lên bội số 1.000 tiếp theo theo CEILING.
 *
 * Ví dụ: 31.001 -> 32.000; 31.200 -> 32.000; 31.000 -> 31.000.
 * Phần tiền dư ra do làm tròn lên thuộc về App/Sàn, không tính vào thu nhập tài xế.
 */
fun Long.roundToNearestThousandVnd(): Long {
    require(this >= 0) { "Số tiền không được âm" }
    return BigDecimal.valueOf(this)
        .divide(ONE_THOUSAND_VND)
        .setScale(0, RoundingMode.CEILING)
        .multiply(ONE_THOUSAND_VND)
        .longValueExact()
}