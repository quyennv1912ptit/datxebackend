package com.nhom15.datxebackend.ride.dto.request

import jakarta.validation.constraints.Size

data class CancelRideRequest(
    @field:Size(max = 255)
    val reason: String? = null,
)
