package com.nhom15.datxebackend.ride.dto.request

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class RideListFilter(
    @field:Size(max = 256)
    val status: String? = null,

    @field:PositiveOrZero
    val page: Int = 0,

    @field:Min(1)
    @field:Max(100)
    val size: Int = 20,

    @field:Size(max = 200) val q: String? = null,
    @param:org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
    val createdFrom: java.time.Instant? = null,
    @param:org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
    val createdTo: java.time.Instant? = null,
)
