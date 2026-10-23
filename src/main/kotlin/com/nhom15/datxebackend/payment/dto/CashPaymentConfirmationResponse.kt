package com.nhom15.datxebackend.payment.dto

import java.time.Instant

data class CashPaymentConfirmationResponse(
    val rideId: Long,
    val amount: Long,
    val status: String,
    val paidAt: Instant?,
)
