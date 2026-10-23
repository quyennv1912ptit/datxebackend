package com.nhom15.datxebackend.ride.dto.response

import java.math.BigDecimal

data class EstimateRideResponse(
    val pricingRuleId: Long,
    val distanceKm: BigDecimal,
    val estimatedDurationMinutes: Long,
    val baseFare: Long,
    val distanceFare: Long,
    val waitingFare: Long,
    val nightSurcharge: Long,
    val returnFee: Long,
    val estimatedFare: Long,
    val discountAmount: Long = 0,
    val payableFare: Long = estimatedFare,
)
