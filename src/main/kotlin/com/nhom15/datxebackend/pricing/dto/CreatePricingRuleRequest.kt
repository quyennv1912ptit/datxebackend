package com.nhom15.datxebackend.pricing.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.NotNull
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.nhom15.datxebackend.common.enums.VehicleType
import java.time.LocalTime

data class CreatePricingRuleRequest(
    @field:PositiveOrZero
    val baseFare: Long,

    @field:PositiveOrZero
    val perKm: Long,

    // No wait telemetry exists yet; accepting a non-zero rate would store a fee we never bill.
    @field:PositiveOrZero
    @field:Max(value = 0, message = "perMinuteWaiting chưa được hỗ trợ; phải bằng 0")
    val perMinuteWaiting: Long,

    @field:Min(0)
    @field:Max(100)
    val nightSurchargePercent: Int = 0,

    val nightStart: LocalTime? = null,

    val nightEnd: LocalTime? = null,

    @field:PositiveOrZero
    val returnFeeForDriver: Long = 0,

    @field:PositiveOrZero
    val minFare: Long,

    @JsonProperty("vehicle_type")
    @JsonAlias("vehicleType")
    @field:NotNull
    val vehicleType: VehicleType,

    val active: Boolean = false,
)
