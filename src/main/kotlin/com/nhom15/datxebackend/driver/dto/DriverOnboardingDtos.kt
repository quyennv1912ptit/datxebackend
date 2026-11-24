package com.nhom15.datxebackend.driver.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import java.time.Instant
import java.util.UUID

data class DriverSkillResponse(
    val id: Long,
    @JsonProperty("vehicle_type") val vehicleType: VehicleType,
    val transmission: Transmission,
    @JsonProperty("license_class") val licenseClass: String?,
    val verified: Boolean,
)

data class DriverVerificationSummary(
    @JsonProperty("verification_status") val verificationStatus: String,
    @JsonProperty("verification_workflow") val verificationWorkflow: String = "EKYC",
)

data class AdminDriverFilter(
    @JsonProperty("verification_status") val verificationStatus: String? = null,
    @JsonProperty("account_status") val accountStatus: String? = null,
    @JsonProperty("withdrawal_frozen") val withdrawalFrozen: Boolean? = null,
    val online: Boolean? = null,
    val flagged: Boolean? = null,
    val search: String? = null,
    val page: Int = 0,
    val size: Int = 20,
)

data class AdminDriverSummary(
    val id: Long,
    @JsonProperty("full_name") val fullName: String?,
    @JsonProperty("phone_number") val phoneNumber: String,
    val email: String?,
    val status: String,
    @JsonProperty("account_status") val accountStatus: String = "ACTIVE",
    @JsonProperty("account_status_reason") val accountStatusReason: String? = null,
    @JsonProperty("withdrawal_frozen") val withdrawalFrozen: Boolean = false,
    @JsonProperty("verification_status") val verificationStatus: String,
    val flagged: Boolean,
    @JsonProperty("total_trips") val totalTrips: Int,
    @JsonProperty("average_rating") val averageRating: Double?,
    @JsonProperty("acceptance_rate") val acceptanceRate: Double?,
    @JsonProperty("completion_rate") val completionRate: Double?,
    @JsonProperty("cancel_rate_30d") val cancelRate30d: Double?,
    @JsonProperty("credit_balance") val creditBalance: Long,
    @JsonProperty("income_balance") val incomeBalance: Long,
    @JsonProperty("created_at") val createdAt: Instant,
)

data class AdminDriverDetail(
    val driver: AdminDriverSummary,
    val verification: DriverVerificationApplicationSummary?,
    val skills: List<DriverSkillResponse>,
)

data class UpdateDriverAccountStatusRequest(
    @JsonProperty("account_status")
    @JsonAlias("accountStatus", "status")
    val accountStatus: String,
    val reason: String? = null,
    @JsonProperty("withdrawal_frozen")
    @JsonAlias("withdrawalFrozen")
    val withdrawalFrozen: Boolean? = null,
)

data class UpdateDriverWithdrawalFreezeRequest(
    @JsonProperty("withdrawal_frozen")
    @JsonAlias("withdrawalFrozen")
    val withdrawalFrozen: Boolean,
    val reason: String? = null,
)

data class DriverVerificationApplicationSummary(val applicationId: UUID, val status: String)
