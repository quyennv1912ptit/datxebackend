package com.nhom15.datxebackend.customer.dto

import com.nhom15.datxebackend.common.domain.GeoPoint
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Địa điểm gợi ý cho màn chọn điểm đón/điểm đến. */
data class CustomerPlaceResponse(
    val id: String,
    val name: String,
    val address: String,
    val provider: String,
    val coordinate: GeoPoint,
    val distanceMeters: Int? = null,
    val useCount: Int? = null,
)

data class ReportPlaceRequest(
    @field:Size(max = 2_048)
    val placeId: String? = null,

    @field:NotBlank
    @field:Size(max = 255)
    val name: String,

    @field:NotBlank
    @field:Size(max = 500)
    val address: String,

    val lat: Double? = null,
    val lng: Double? = null,

    @field:NotBlank
    @field:Size(min = 5, max = 500)
    val reason: String,
)

data class ReportPlaceResponse(
    val ticketId: Long,
    val code: String,
)
