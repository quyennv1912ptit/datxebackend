package com.nhom15.datxebackend.vehicle.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class CreateVehicleRequest(
    @JsonProperty("plate_number")
    @JsonAlias("plateNumber")
    @field:NotBlank
    @field:Size(max = 20)
    val plateNumber: String,

    @JsonProperty("transmission_type")
    @JsonAlias("transmissionType")
    @field:NotNull
    val transmissionType: Transmission,

    @JsonProperty("vehicle_type")
    @JsonAlias("vehicleType")
    @field:NotNull
    val vehicleType: VehicleType,

    @JsonProperty("engine_cc")
    @JsonAlias("engineCc")
    @field:Positive
    val engineCc: Int? = null,

    @JsonProperty("brand")
    @JsonAlias("make", "manufacturer")
    @field:Size(max = 100)
    val brand: String? = null,

    @field:Size(max = 100)
    val model: String? = null,

    @field:Size(max = 50)
    val color: String? = null,
)

data class UpdateVehicleRequest(
    @JsonProperty("plate_number")
    @JsonAlias("plateNumber")
    @field:NotBlank
    @field:Size(max = 20)
    val plateNumber: String,

    @JsonProperty("transmission_type")
    @JsonAlias("transmissionType")
    @field:NotNull
    val transmissionType: Transmission,

    @JsonProperty("vehicle_type")
    @JsonAlias("vehicleType")
    @field:NotNull
    val vehicleType: VehicleType,

    @JsonProperty("engine_cc")
    @JsonAlias("engineCc")
    @field:Positive
    val engineCc: Int? = null,

    @JsonProperty("brand")
    @JsonAlias("make", "manufacturer")
    @field:Size(max = 100)
    val brand: String? = null,

    @field:Size(max = 100)
    val model: String? = null,

    @field:Size(max = 50)
    val color: String? = null,
)

data class AddVehicleImageRequest(
    @JsonProperty("secure_url")
    @JsonAlias("secureUrl")
    @field:NotBlank
    @field:Size(max = 2048)
    val secureUrl: String,

    @JsonProperty("public_id")
    @JsonAlias("publicId")
    @field:NotBlank
    @field:Size(max = 512)
    val publicId: String,
)

data class VehicleImageResponse(
    val id: Long,
    @JsonProperty("secure_url") val secureUrl: String,
)

data class VehicleResponse(
    val id: Long,
    @JsonProperty("plate_number") val plateNumber: String?,
    @JsonProperty("transmission_type") val transmissionType: String?,
    @JsonProperty("vehicle_type") val vehicleType: String?,
    @JsonProperty("engine_cc") val engineCc: Int?,
    val brand: String?,
    val model: String?,
    val color: String?,
    val images: List<VehicleImageResponse> = emptyList(),
)
