package com.nhom15.datxebackend.ride.dto.request

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class CreateHandoverReportRequest(
    @field:PositiveOrZero
    val odometer: Long,

    @field:PositiveOrZero
    @field:Max(100)
    val fuelLevel: Int? = null,

    @field:Size(max = 2_000)
    val notes: String? = null,

    @field:Valid
    @field:Size(min = 4, max = 5)
    val photos: List<HandoverPhotoRequest>,
)
