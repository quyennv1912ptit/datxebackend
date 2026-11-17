package com.nhom15.datxebackend.driver.verification

import java.time.LocalDate
import java.util.UUID

data class VerificationFeedEnvelope(val success: Boolean, val data: VerificationFeed?)
data class VerificationHeadEnvelope(val success: Boolean, val data: VerificationHead?)
data class VerificationHead(val streamId: UUID, val head: Long)
data class VerificationFeed(
    val streamId: UUID,
    val head: Long,
    val events: List<VerificationEvent>,
    val hasMore: Boolean,
)
data class VerificationEvent(
    val seq: Long,
    val applicationId: UUID,
    val revision: Long,
    val ownerUid: String,
    val status: VerificationStatus,
    val entitlements: List<LicenseEntitlement>,
    val cccdExpiresOn: LocalDate? = null,
    val capabilityReview: CapabilityReview? = null,
)
enum class VerificationStatus { SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED, NEEDS_MORE_INFO, REVOKED }
enum class LicenseValidity { NO_EXPIRY, HAS_EXPIRY, UNKNOWN, EXPIRED }

data class LicenseEntitlement(
    val licenseClass: String,
    val kind: LicenseValidity,
    val expiresOn: LocalDate?,
) {
    fun validity(today: LocalDate): LicenseValidity = when (kind) {
        LicenseValidity.NO_EXPIRY -> if (expiresOn == null) kind else LicenseValidity.UNKNOWN
        LicenseValidity.HAS_EXPIRY -> when {
            expiresOn == null -> LicenseValidity.UNKNOWN
            expiresOn.isBefore(today) -> LicenseValidity.EXPIRED
            else -> LicenseValidity.HAS_EXPIRY
        }
        else -> kind
    }
}
data class DriverVerificationProjection(
    val status: VerificationStatus, val entitlements: List<LicenseEntitlement>,
    val cccdExpiresOn: LocalDate? = null,
    val revision: Long = 0,
    val capabilities: List<ApprovedCapability> = emptyList(),
    val enabledGroups: Set<String> = emptySet(),
    val preferenceVersion: Long = 0,
)
