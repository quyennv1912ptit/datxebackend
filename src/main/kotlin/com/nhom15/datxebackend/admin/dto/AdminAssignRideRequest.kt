package com.nhom15.datxebackend.admin.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Positive

data class AdminAssignRideRequest(
    @JsonProperty("driver_id")
    @JsonAlias("driverId")
    @field:Positive
    val driverId: Long,
)
