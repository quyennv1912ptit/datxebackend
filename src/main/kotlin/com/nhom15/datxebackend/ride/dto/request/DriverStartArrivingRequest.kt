package com.nhom15.datxebackend.ride.dto.request

import jakarta.validation.constraints.Size

data class DriverStartArrivingRequest(
    @field:Size(max = 1_000)
    val note: String? = null,
)
