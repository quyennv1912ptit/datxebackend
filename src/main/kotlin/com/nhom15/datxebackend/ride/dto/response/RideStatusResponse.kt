package com.nhom15.datxebackend.ride.dto.response

import com.nhom15.datxebackend.common.enums.RideStatus
import java.time.Instant

data class RideStatusResponse(
    val rideId: Long,
    val previousStatus: RideStatus,
    val currentStatus: RideStatus,
    val changedAt: Instant,
)
