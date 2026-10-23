package com.nhom15.datxebackend.ride.dto.request

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.ride.entity.RideType
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Future
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreateRideRequest(
    @field:Positive
    val vehicleId: Long,

    val type: RideType = RideType.INSTANT,

    @field:Future
    val scheduledAt: Instant? = null,

    @field:NotBlank
    @field:Size(max = 255)
    val pickupAddress: String,

    @field:DecimalMin("-90.0")
    @field:DecimalMax("90.0")
    val pickupLat: Double,

    @field:DecimalMin("-180.0")
    @field:DecimalMax("180.0")
    val pickupLng: Double,

    @field:NotBlank
    @field:Size(max = 255)
    val dropoffAddress: String,

    @field:DecimalMin("-90.0")
    @field:DecimalMax("90.0")
    val dropoffLat: Double,

    @field:DecimalMin("-180.0")
    @field:DecimalMax("180.0")
    val dropoffLng: Double,

    val paymentMethod: PaymentMethod = PaymentMethod.CASH,

    @field:Size(max = 500)
    val note: String? = null,

    @JsonProperty("voucher_code")
    @JsonAlias("voucherCode")
    @field:Size(max = 255)
    val voucherCode: String? = null,
)
