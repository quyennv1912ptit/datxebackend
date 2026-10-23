package com.nhom15.datxebackend.driver.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

enum class DocumentVerificationStatus { PENDING, APPROVED, REJECTED }
enum class DriverVerificationStatus { APPROVED, REJECTED }

data class SubmitDriverDocumentRequest(
    @JsonProperty("document_type")
    @JsonAlias("documentType")
    @field:NotBlank
    @field:Size(max = 50)
    @field:Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]*")
    val documentType: String,

    @JsonProperty("document_number")
    @JsonAlias("documentNumber")
    @field:Size(max = 100)
    val documentNumber: String? = null,

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

    @JsonProperty("expires_at")
    @JsonAlias("expiresAt")
    val expiresAt: LocalDate? = null,
)

data class DriverDocumentResponse(
    val id: Long,
    @JsonProperty("driver_id") val driverId: Long,
    @JsonProperty("document_type") val documentType: String,
    @JsonProperty("document_number") val documentNumber: String?,
    @JsonProperty("secure_url") val secureUrl: String,
    @JsonProperty("public_id") val publicId: String,
    @JsonProperty("verification_status") val verificationStatus: DocumentVerificationStatus,
    @JsonProperty("rejection_reason") val rejectionReason: String?,
    @JsonProperty("expires_at") val expiresAt: LocalDate?,
    @JsonProperty("is_current") val isCurrent: Boolean,
    @JsonProperty("reviewed_at") val reviewedAt: Instant?,
    @JsonProperty("created_at") val createdAt: Instant,
)

data class DriverSkillInput(
    @JsonProperty("vehicle_type")
    @JsonAlias("vehicleType")
    @field:NotNull
    val vehicleType: VehicleType,

    @field:NotNull
    val transmission: Transmission,

    @JsonProperty("license_class")
    @JsonAlias("licenseClass")
    @field:Size(max = 10)
    val licenseClass: String? = null,
)

data class UpdateDriverSkillsRequest(
    @field:Size(max = 20)
    @field:Valid
    val skills: List<DriverSkillInput>,
)

data class DriverSkillResponse(
    val id: Long,
    @JsonProperty("vehicle_type") val vehicleType: VehicleType,
    val transmission: Transmission,
    @JsonProperty("license_class") val licenseClass: String?,
    val verified: Boolean,
)

data class DriverVerificationSummary(
    @JsonProperty("verification_status") val verificationStatus: String,
    @JsonProperty("required_document_types") val requiredDocumentTypes: List<String>,
    @JsonProperty("missing_documents") val missingDocuments: List<String>,
    @JsonProperty("unverified_skills") val unverifiedSkills: List<DriverSkillResponse>,
)

data class ReviewDriverDocumentRequest(
    val status: DocumentVerificationStatus,
    @JsonProperty("rejection_reason")
    @JsonAlias("rejectionReason")
    @field:Size(max = 1000)
    val rejectionReason: String? = null,
)

data class VerifyDriverSkillRequest(val verified: Boolean)

data class SetDriverVerificationStatusRequest(
    val status: DriverVerificationStatus,
    @JsonProperty("rejection_reason")
    @JsonAlias("rejectionReason")
    @field:Size(max = 1000)
    val rejectionReason: String? = null,
)

data class AdminDriverFilter(
    @JsonProperty("verification_status") val verificationStatus: String? = null,
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
    val documents: List<DriverDocumentResponse>,
    val skills: List<DriverSkillResponse>,
)

data class AdminDriverDocumentQueueItem(
    val document: DriverDocumentResponse,
    @JsonProperty("driver_name") val driverName: String?,
    @JsonProperty("driver_phone") val driverPhone: String,
)
