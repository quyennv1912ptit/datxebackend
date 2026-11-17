package com.nhom15.datxebackend.voucher.dto

import java.time.Instant

data class VoucherResponse(
    val id: Long,
    val code: String?,
    val campaignCode: String? = null,
    val campaignTitle: String? = null,
    val description: String? = null,
    val milestone: Int = 0,
    val claimSequence: Int? = null,
    val campaignClaimLimit: Int? = null,
    val discountType: String,
    val value: Long,
    val maxDiscount: Long?,
    val minimumOrderAmount: Long,
    val usageCount: Int,
    val usageLimit: Int?,
    val validFrom: Instant,
    val validTo: Instant,
    val source: String,
)

data class AppliedVoucher(
    val voucherId: Long,
    val code: String?,
    val discountAmount: Long,
)

data class VoucherCampaignResponse(
    val id: Long,
    val code: String,
    val campaignTitle: String? = null,
    val description: String,
    val discountType: String,
    val value: Long,
    val maxDiscount: Long?,
    val minimumOrderAmount: Long,
    val claimedCount: Int,
    val claimLimit: Int,
    val validFrom: Instant,
    val validTo: Instant,
    val budgetLimit: Long,
    val budgetRemaining: Long,
)