package com.nhom15.datxebackend.ride.dto.response

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.ride.entity.RideType
import java.time.Instant

data class RideSummaryResponse(
    val id: Long,
    val pickupAddress: String?,
    val dropoffAddress: String?,
    val pickupLat: Double?,
    val pickupLng: Double?,
    val dropoffLat: Double?,
    val dropoffLng: Double?,
    val type: RideType,
    val status: RideStatus,
    val estimatedFare: Long,
    val finalFare: Long?,
    val discountAmount: Long = 0,
    val payableFare: Long = estimatedFare,
    val scheduledAt: Instant?,
    val createdAt: Instant,
    val hasRated: Boolean = false,
    val myRatingStars: Int? = null,
)
