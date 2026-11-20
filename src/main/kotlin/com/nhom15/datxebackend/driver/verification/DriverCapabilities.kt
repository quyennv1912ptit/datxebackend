package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.vehicle.catalog.VehicleCatalog
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class CapabilityReview(
    val policyVersion: String,
    // Approval requests omit this value. The service binds it to the stored submission before granting capabilities.
    val submissionRevision: Long = 0,
    val issuedOn: LocalDate,
    val restriction: LicenseRestriction,
    val restrictionCode: String? = null,
    val selectedGroupIds: List<String>,
)

data class ApprovedCapability(
    val groupId: String,
    val applicationId: UUID,
    val submissionRevision: Long,
    val approvalRevision: Long,
    val policyVersion: String,
    val entitlements: List<LicenseEntitlement>,
) {
    fun valid(today: LocalDate) = policyVersion == LicenseEligibilityPolicy.VERSION &&
        entitlements.any { it.validity(today) in setOf(LicenseValidity.NO_EXPIRY, LicenseValidity.HAS_EXPIRY) }
}

internal val supportedCapabilityGroups
    get() = VehicleCatalog.groups.filter { it.active && it.supported }
internal fun capabilityToday(): LocalDate = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))

internal fun grants(event: VerificationEvent, today: LocalDate): List<ApprovedCapability> {
    val review = event.capabilityReview ?: return emptyList()
    if (event.status != VerificationStatus.APPROVED || review.policyVersion != LicenseEligibilityPolicy.VERSION ||
        review.submissionRevision !in 1L until event.revision || event.cccdExpiresOn?.isBefore(today) == true ||
        review.selectedGroupIds.size !in 1..20
    ) return emptyList()
    // A restriction code must be attributable to one entitlement in this document.
    if ((review.restrictionCode != null || review.restriction != LicenseRestriction.VERIFIED_NONE) &&
        event.entitlements.size != 1) return emptyList()
    val policy = LicenseEligibilityPolicy()
    return supportedCapabilityGroups.filter { it.id in review.selectedGroupIds }.mapNotNull { group ->
        val valid = event.entitlements.filter { entry ->
            val evidence = LicenseEvidence(listOf(entry.licenseClass), review.issuedOn, entry.expiresOn,
                when (entry.validity(today)) {
                    LicenseValidity.NO_EXPIRY -> true
                    LicenseValidity.HAS_EXPIRY -> false
                    else -> return@filter false
                }, review.restriction, review.restrictionCode)
            policy.evaluate(evidence, group, LicensePurpose.PAID_DRIVER, today).status == Eligibility.ELIGIBLE
        }
        if (valid.isEmpty()) null else ApprovedCapability(group.id, event.applicationId,
            review.submissionRevision, event.revision, review.policyVersion, valid)
    }
}

internal fun DriverVerificationProjection.effectiveGroups(today: LocalDate): Set<String> =
    if (status != VerificationStatus.APPROVED || cccdExpiresOn?.isBefore(today) == true) emptySet()
    else capabilities.filter { it.approvalRevision == revision && it.groupId in enabledGroups && it.valid(today) }
        .mapTo(mutableSetOf()) { it.groupId }

internal fun coversTrip(groups: Set<String>, type: VehicleType, transmission: Transmission?): Boolean {
    val possible = supportedCapabilityGroups.filter {
        it.vehicleType == type && (transmission == null || it.transmission == null || it.transmission == transmission)
    }
    return possible.isNotEmpty() && possible.all { it.id in groups }
}

internal fun coversOnline(groups: Set<String>, type: VehicleType): Boolean =
    supportedCapabilityGroups.any { it.vehicleType == type && it.id in groups }
