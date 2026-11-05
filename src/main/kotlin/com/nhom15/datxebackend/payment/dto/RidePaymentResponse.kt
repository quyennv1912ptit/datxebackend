package com.nhom15.datxebackend.payment.dto

import java.time.Instant

data class RidePaymentResponse(val amount: Long, val method: String, val status: String, val paidAt: Instant?)
