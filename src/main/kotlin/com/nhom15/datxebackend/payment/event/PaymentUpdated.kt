package com.nhom15.datxebackend.payment.event

import com.nhom15.datxebackend.common.enums.PaymentMethod

data class PaymentUpdated(val rideId: Long, val customerId: Long, val driverId: Long,
    val fromMethod: PaymentMethod, val method: PaymentMethod, val status: String, val amount: Long)
