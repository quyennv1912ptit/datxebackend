package com.nhom15.datxebackend.voucher.dto

import com.nhom15.datxebackend.common.enums.PaymentMethod
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.Instant

data class CreateAdminVoucherRequest(
    val code: String? = null,
    val campaignTitle: String? = null,
    val description: String = "",
    @field:NotBlank
    val discountType: String,
    @field:Positive
    val value: Long,
    @field:Positive
    val maxDiscount: Long? = null,
    @field:Min(0)
    val minimumOrderAmount: Long = 0,
    @field:Positive
    val usageLimit: Int? = null,
    @field:Min(0)
    val campaignBudgetLimit: Long = 0,
    @field:NotNull
    val validFrom: Instant,
    @field:NotNull
    val validTo: Instant,
    val allowedPaymentMethod: PaymentMethod? = null,
    @field:Positive
    val customerId: Long? = null,
)

data class AdminVoucherCreatedResponse(
    val id: Long,
    val code: String?,
    val campaignTitle: String? = null,
    val source: String,
    val active: Boolean = true,
    val ownerCustomerId: Long?,
    val discountType: String,
    val value: Long,
    val maxDiscount: Long?,
    val minimumOrderAmount: Long,
    val usageLimit: Int?,
    val validFrom: Instant,
    val validTo: Instant,
    val allowedPaymentMethod: String?,
    val description: String = "",
    val campaignClaimCount: Int = 0,
    val campaignUsedCount: Int = 0,
    val campaignHeldCount: Int = 0,
    val campaignCancelledCount: Int = 0,
    val campaignCancelledAmount: Long = 0,
    val campaignBudgetLimit: Long? = null,
    val campaignHeldAmount: Long = 0,
    val campaignSpentAmount: Long = 0,
    val campaignReleasedAmount: Long = 0,
    val systemReserveAmount: Long = 0,
)

data class CampaignClaimantResponse(
    val voucherId: Long,
    val customerId: Long,
    val fullName: String,
    val phone: String,
    val claimedAt: Instant,
    val status: String,
    val rideId: Long? = null,
    val discountAmount: Long? = null,
)

data class BudgetLedgerItemResponse(
    val id: Long,
    val action: String,
    val amount: Long,
    val campaignId: Long?,
    val campaignCode: String?,
    val voucherId: Long?,
    val voucherLabel: String?,
    val customerName: String?,
    val rideId: Long?,
    val note: String?,
    val createdAt: Instant,
)