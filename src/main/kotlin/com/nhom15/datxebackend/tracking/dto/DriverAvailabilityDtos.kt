package com.nhom15.datxebackend.tracking.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.VehicleType

data class DriverAvailabilityRequest(
    val online: Boolean,
    @JsonProperty("vehicle_type")
    @JsonAlias("vehicleType")
    val vehicleType: VehicleType? = null,
)

data class DriverAvailabilityResponse(val status: DriverStatus)
