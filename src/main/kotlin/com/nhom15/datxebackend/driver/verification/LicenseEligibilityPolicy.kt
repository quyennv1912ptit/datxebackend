package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.vehicle.catalog.EngineType
import com.nhom15.datxebackend.vehicle.catalog.VehicleCategory
import com.nhom15.datxebackend.vehicle.catalog.VehicleGroup
import org.springframework.stereotype.Service
import java.time.LocalDate

enum class Eligibility { ELIGIBLE, INELIGIBLE, REQUIRES_MANUAL_REVIEW }
enum class LicenseRestriction { UNKNOWN, VERIFIED_NONE, AUTO_ONLY, LEGACY_A1_LIMIT, OTHER }
enum class LicensePurpose { PRIVATE, PAID_DRIVER }

data class LicenseEvidence(
    val licenseClasses: List<String>,
    val issuedOn: LocalDate?,
    val expiresOn: LocalDate?,
    val noExpiry: Boolean?,
    val restriction: LicenseRestriction = LicenseRestriction.UNKNOWN,
    val restrictionCode: String? = null,
)

data class LicenseDecision(
    val status: Eligibility,
    val reasonCodes: List<String>,
    val policyVersion: String = LicenseEligibilityPolicy.VERSION,
)

@Service
class LicenseEligibilityPolicy {
    companion object {
        const val VERSION = "VN_36_2024_108_2026_MVP_1"
        private val transition = LocalDate.of(2025, 1, 1)
        private val newCarClasses = setOf("B", "C1", "C", "D1", "D2", "D")
        private val oldCarClasses = setOf("B1", "B2", "C", "D", "E")
        private val oldClasses = oldCarClasses + setOf("A1", "A2", "A3", "A4", "FB1", "FB2", "FC", "FD", "FE")
        private val newClasses = newCarClasses + setOf("A", "A1", "B1", "BE", "C1E", "CE", "D1E", "D2E", "DE")
    }

    fun evaluate(license: LicenseEvidence, vehicle: VehicleGroup, purpose: LicensePurpose, today: LocalDate): LicenseDecision {
        if (!vehicle.active || !vehicle.supported) return deny("VEHICLE_OUTSIDE_MVP")
        if (license.expiresOn?.isBefore(today) == true && license.noExpiry != true) return deny("LICENSE_EXPIRED")
        val issue = license.issuedOn ?: return review("ISSUE_DATE_MISSING")
        if (issue > today || license.expiresOn?.isBefore(issue) == true) return review("LICENSE_DATES_INCONSISTENT")
        if (license.noExpiry == null || license.noExpiry != (license.expiresOn == null)) return review("EXPIRY_UNVERIFIED")
        val current = issue >= transition
        val classes = license.licenseClasses.toSet()
        if (classes.isEmpty() || !classes.all { it in (if (current) newClasses else oldClasses) }) {
            return review("CLASS_OR_ISSUE_REGIME_UNVERIFIED")
        }
        if (classes.size > 1 && (license.restrictionCode != null || license.restriction != LicenseRestriction.VERIFIED_NONE)) {
            return review("RESTRICTION_SCOPE_UNVERIFIED")
        }
        val eligible = classes.sorted().map { grade -> classScope(grade, current, license, vehicle, purpose) }
        val possible = eligible.firstOrNull { it.status == Eligibility.ELIGIBLE }
            ?: return eligible.firstOrNull { it.status == Eligibility.REQUIRES_MANUAL_REVIEW }
                ?: eligible.first()

        val restriction = when (license.restrictionCode) {
            null -> license.restriction
            "A.03" -> if (current && classes == setOf("A")) LicenseRestriction.LEGACY_A1_LIMIT else LicenseRestriction.OTHER
            "B.01" -> if (current && classes == setOf("B")) LicenseRestriction.AUTO_ONLY else LicenseRestriction.OTHER
            else -> LicenseRestriction.OTHER
        }
        return when (restriction) {
            LicenseRestriction.UNKNOWN, LicenseRestriction.OTHER -> review("RESTRICTION_UNVERIFIED")
            LicenseRestriction.AUTO_ONLY -> when (vehicle.transmission) {
                Transmission.AUTO -> possible
                Transmission.MANUAL -> deny("AUTOMATIC_ONLY")
                null -> review("VEHICLE_TRANSMISSION_UNKNOWN")
            }
            LicenseRestriction.LEGACY_A1_LIMIT -> {
                if (!current || "A" !in classes || vehicle.category != VehicleCategory.MOTORCYCLE) {
                    review("RESTRICTION_SCOPE_UNVERIFIED")
                } else {
                    motorcycleScope("A1", false, vehicle)
                }
            }
            LicenseRestriction.VERIFIED_NONE -> possible
        }
    }

    private fun classScope(
        grade: String, current: Boolean, license: LicenseEvidence, vehicle: VehicleGroup, purpose: LicensePurpose,
    ): LicenseDecision {
        val unlimited = grade in (if (current) setOf("A1", "A", "B1") else setOf("A1", "A2", "A3"))
        if (license.noExpiry != unlimited) return review("EXPIRY_CLASS_INCONSISTENT")
        return when (vehicle.category) {
            VehicleCategory.MOTORCYCLE -> motorcycleScope(grade, current, vehicle)
            VehicleCategory.PASSENGER_CAR -> carScope(grade, current, vehicle, purpose)
            VehicleCategory.TRUCK -> truckScope(grade, current, vehicle)
            VehicleCategory.BUS -> deny("VEHICLE_OUTSIDE_MVP")
        }
    }

    private fun motorcycleScope(grade: String, current: Boolean, vehicle: VehicleGroup): LicenseDecision {
        if (vehicle.subtype != "TWO_WHEEL") return deny("VEHICLE_OUTSIDE_MVP")
        val high = when (vehicle.engineType) {
            EngineType.COMBUSTION -> {
                val cc = vehicle.displacementCc?.takeIf { it >= 50 } ?: return review("MOTORCYCLE_SPEC_UNVERIFIED")
                if (current) cc > 125 else cc >= 175
            }
            EngineType.ELECTRIC -> {
                val kw = vehicle.electricMotorKw?.takeIf { it.isFinite() && it >= 4 } ?: return review("MOTORCYCLE_SPEC_UNVERIFIED")
                if (current) kw > 11 else kw >= 14
            }
            else -> return review("MOTORCYCLE_SPEC_UNVERIFIED")
        }
        val full = if (current) "A" else "A2"
        val lower = if (current) setOf("A1", "B1") else setOf("A1", "A3")
        return if (grade == full || (!high && grade in lower)) allow() else deny("MOTORCYCLE_CLASS_LIMIT")
    }

    private fun carScope(
        grade: String, current: Boolean, vehicle: VehicleGroup, purpose: LicensePurpose,
    ): LicenseDecision {
        if ((current && grade.endsWith("E")) || (!current && grade.startsWith("F"))) {
            return review("TRAILER_ENTITLEMENT_REVIEW")
        }
        if (grade !in (if (current) newCarClasses else oldCarClasses)) return deny("PASSENGER_CAR_CLASS_REQUIRED")
        if (!current && grade == "B1" && purpose == LicensePurpose.PAID_DRIVER) return deny("LEGACY_B1_NON_PROFESSIONAL")
        val seats = vehicle.passengerSeats?.takeIf { it > 0 } ?: return review("PASSENGER_SEATS_UNVERIFIED")
        if (seats > 8) return deny("PASSENGER_SEAT_LIMIT")
        val trailer = vehicle.trailerMassKg?.takeIf { it >= 0 } ?: return review("TRAILER_MASS_UNVERIFIED")
        if (trailer > 750) return deny("TRAILER_CLASS_LIMIT")
        if (trailer > 0 || vehicle.subtype != "PRIVATE_CAR") return deny("VEHICLE_OUTSIDE_MVP")
        if (vehicle.transmission == null) return review("VEHICLE_TRANSMISSION_UNKNOWN")
        return allow()
    }

    private fun truckScope(grade: String, current: Boolean, vehicle: VehicleGroup): LicenseDecision {
        if (!current) return review("LEGACY_TRUCK_PAYLOAD_REQUIRED")
        val mass = vehicle.designedMassKg?.takeIf { it > 0 } ?: return review("DESIGNED_MASS_UNVERIFIED")
        val trailer = vehicle.trailerMassKg?.takeIf { it >= 0 } ?: return review("TRAILER_MASS_UNVERIFIED")
        if (trailer > 750) return review("TRAILER_ENTITLEMENT_REVIEW")
        val allowed = when {
            mass <= 3500 -> newCarClasses
            mass <= 7500 -> newCarClasses - "B"
            else -> newCarClasses - setOf("B", "C1")
        }
        return if (grade in allowed) allow() else deny("DESIGNED_MASS_CLASS_LIMIT")
    }

    private fun allow() = LicenseDecision(Eligibility.ELIGIBLE, listOf("LICENSE_CLASS_SCOPE_MATCH"))
    private fun deny(reason: String) = LicenseDecision(Eligibility.INELIGIBLE, listOf(reason))
    private fun review(reason: String) = LicenseDecision(Eligibility.REQUIRES_MANUAL_REVIEW, listOf(reason))
}
