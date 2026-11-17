package com.nhom15.datxebackend.common.port

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class VehicleBookingDetails @JsonCreator constructor(
    @JsonProperty("brand") val brand: String?,
    @JsonProperty("model") val model: String?,
    @JsonProperty("color") val color: String?,
    @JsonProperty("engineCc") val engineCc: Int?,
    @JsonProperty("engineType") val engineType: String?,
    @JsonProperty("electricMotorKw") val electricMotorKw: Double?,
    @JsonProperty("passengerSeats") val passengerSeats: Int?,
    @JsonProperty("designedMassKg") val designedMassKg: Int?,
    @JsonProperty("trailerMassKg") val trailerMassKg: Int?,
    @JsonProperty("source") val source: String,
    @JsonProperty("capabilityGroupId") val capabilityGroupId: String?,
    @JsonProperty("capabilityStatus") val capabilityStatus: String,
    @JsonProperty("catalogVersion") val catalogVersion: String,
)
