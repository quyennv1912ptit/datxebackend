package com.nhom15.datxebackend.ride.dto.response

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.ride.entity.RideType
import java.time.Instant

data class RideSummaryResponse(
    val id: Long,
    val pickupAddress: String?,
    val dropoffAddress: String?,
    val type: RideType,
    val status: RideStatus,
    val estimatedFare: Long,
    val finalFare: Long?,
    val scheduledAt: Instant?,
    val createdAt: Instant,
)
