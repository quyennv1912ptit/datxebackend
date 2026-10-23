package com.nhom15.datxebackend.customer.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.nhom15.datxebackend.common.enums.PaymentMethod
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Past
import jakarta.validation.constraints.Size
import java.time.LocalDate

data class UpdateCustomerProfileRequest(
    @JsonProperty("full_name")
    @JsonAlias("fullName")
    @field:NotBlank
    @field:Size(max = 255)
    val fullName: String,

    @field:Email
    @field:Size(max = 255)
    val email: String? = null,

    @JsonProperty("date_of_birth")
    @JsonAlias("dateOfBirth")
    @field:Past
    val dateOfBirth: LocalDate? = null,

    @field:Size(max = 30)
    val gender: String? = null,

    @JsonProperty("default_payment_method")
    @JsonAlias("defaultPaymentMethod")
    val defaultPaymentMethod: PaymentMethod? = null,
)

data class UpdateCustomerAvatarRequest(
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

data class CustomerProfileResponse(
    val id: Long,
    @JsonProperty("full_name") val fullName: String?,
    val email: String?,
    @JsonProperty("phone_number") val phoneNumber: String?,
    @JsonProperty("date_of_birth") val dateOfBirth: LocalDate?,
    val gender: String?,
    @JsonProperty("default_payment_method") val defaultPaymentMethod: PaymentMethod?,
    @JsonProperty("avatar_url") val avatarUrl: String?,
)
