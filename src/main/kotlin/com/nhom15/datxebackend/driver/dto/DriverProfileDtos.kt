package com.nhom15.datxebackend.driver.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UpdateDriverProfileRequest(
    @JsonProperty("license_number")
    @JsonAlias("licenseNumber")
    @field:NotBlank
    @field:Size(max = 50)
    val licenseNumber: String,

    @JsonProperty("license_class")
    @JsonAlias("licenseClass")
    @field:NotBlank
    @field:Size(max = 10)
    val licenseClass: String,

    @JsonProperty("years_of_experience")
    @JsonAlias("yearsOfExperience")
    @field:Min(0)
    val yearsOfExperience: Int,

    @JsonProperty("license_image_url")
    @JsonAlias("licenseImageUrl")
    @field:Size(max = 2048)
    val licenseImageUrl: String? = null,
)

data class DriverProfileResponse(
    val id: Long,
    @JsonProperty("license_number") val licenseNumber: String?,
    @JsonProperty("license_class") val licenseClass: String?,
    @JsonProperty("years_of_experience") val yearsOfExperience: Int?,
    @JsonProperty("license_image_url") val licenseImageUrl: String?,
    @JsonProperty("license_verified") val licenseVerified: Boolean,
    @JsonProperty("license_review_status") val licenseReviewStatus: String,
)
