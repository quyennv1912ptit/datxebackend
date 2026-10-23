package com.nhom15.datxebackend.rating.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class CreateRideRatingRequest(
    @field:Min(1)
    @field:Max(5)
    val stars: Int,
    @field:Size(max = 2000)
    val comment: String? = null,
    @field:Size(max = 10)
    val tags: List<@jakarta.validation.constraints.Size(max = 50) String>? = null,
    @field:PositiveOrZero
    val tip: Long = 0,
)

