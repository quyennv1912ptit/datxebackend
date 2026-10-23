package com.nhom15.datxebackend.ride.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CancelRideRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val reason: String,
)
