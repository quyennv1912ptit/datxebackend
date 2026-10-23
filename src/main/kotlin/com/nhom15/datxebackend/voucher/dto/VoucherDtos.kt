package com.nhom15.datxebackend.voucher.dto

import java.time.Instant

data class VoucherResponse(
    val id: Long,
    val code: String,
    val discountType: String,
    val value: Long,
    val maxDiscount: Long?,
    val minimumOrderAmount: Long,
    val usageCount: Int,
    val usageLimit: Int?,
    val validFrom: Instant,
    val validTo: Instant,
)

data class AppliedVoucher(
    val voucherId: Long,
    val code: String,
    val discountAmount: Long,
)
