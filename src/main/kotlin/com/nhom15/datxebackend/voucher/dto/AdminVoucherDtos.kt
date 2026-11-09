package com.nhom15.datxebackend.voucher.dto

import com.nhom15.datxebackend.common.enums.PaymentMethod
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import java.time.Instant

data class CreateAdminVoucherRequest(
    val code: String? = null,

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
    val source: String,
    val ownerCustomerId: Long?,
    val discountType: String,
    val value: Long,
    val maxDiscount: Long?,
    val minimumOrderAmount: Long,
    val usageLimit: Int?,
    val validFrom: Instant,
    val validTo: Instant,
    val allowedPaymentMethod: String?,
)
