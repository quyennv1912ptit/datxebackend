package com.nhom15.datxebackend.driver.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class DriverProfileResponse(
    val id: Long,
    @JsonProperty("avatar_url") val avatarUrl: String? = null,
    @JsonProperty("license_number") val licenseNumber: String?,
    @JsonProperty("license_class") val licenseClass: String?,
    @JsonProperty("years_of_experience") val yearsOfExperience: Int?,
    @JsonProperty("license_verified") val licenseVerified: Boolean,
    @JsonProperty("bank_name") val bankName: String? = null,
    @JsonProperty("bank_account_number") val bankAccountNumber: String? = null,
    @JsonProperty("account_status") val accountStatus: String = "ACTIVE",
    @JsonProperty("account_status_reason") val accountStatusReason: String? = null,
    @JsonProperty("withdrawal_frozen") val withdrawalFrozen: Boolean = false,
)


data class UpdateDriverIdentityRequest(
    @field:NotBlank
    @field:Size(max = 120)
    val fullName: String,
    @field:Email
    @field:Size(max = 255)
    val email: String? = null,
    @JsonAlias("bank_name")
    @field:Size(max = 255)
    val bankName: String? = null,
    @JsonAlias("bank_account_number")
    @field:Size(max = 255)
    val bankAccountNumber: String? = null,
)

data class DriverIdentityResponse(val fullName: String?, val email: String?, val bankName: String? = null, val bankAccountNumber: String? = null)

data class UpdateDriverAvatarRequest(
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
