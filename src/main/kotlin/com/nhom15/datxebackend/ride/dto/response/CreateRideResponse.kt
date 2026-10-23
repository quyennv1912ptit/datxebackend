package com.nhom15.datxebackend.ride.dto.response

import com.nhom15.datxebackend.common.enums.RideStatus
import java.time.Instant

data class CreateRideResponse(
    val id: Long,
    val status: RideStatus,
    val estimatedFare: Long,
    val createdAt: Instant,
    val discountAmount: Long = 0,
    val payableFare: Long = estimatedFare,
)
