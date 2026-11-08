package com.nhom15.datxebackend.payment.dto

data class DriverRideEarnings(val netAmount: Long, val commissionAmount: Long,
    val creditedToCash: Boolean, val paid: Boolean)
