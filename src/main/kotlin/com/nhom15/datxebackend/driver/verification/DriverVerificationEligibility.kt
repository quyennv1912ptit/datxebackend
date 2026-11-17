package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.vehicle.catalog.VehicleCatalog
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Service
class DriverVerificationEligibility @Autowired constructor(
    private val projection: VerificationProjectionRepository?,
    private val sync: VerificationSync? = null,
) {
    constructor() : this(null, null)

    val synchronizedWorkflow: Boolean get() = projection != null

    fun currentAtSource(): Boolean {
        val source = projection ?: return true
        // When external HTTP verification sync is off, PostgreSQL projection is the direct single source of truth.
        val externalSync = sync ?: return true
        val local = source.synchronizedHead() ?: return false
        return externalSync.confirms(local)
    }

    fun displayedStatus(driver: Driver): String {
        if (driver.verificationStatus == "SUSPENDED") return "SUSPENDED"
        val source = projection ?: return driver.verificationStatus ?: "PENDING"
        return when (source.current(driver.firebaseUid)?.status) {
            VerificationStatus.APPROVED -> "APPROVED"
            VerificationStatus.REJECTED, VerificationStatus.REVOKED -> "REJECTED"
            else -> "PENDING"
        }
    }

    fun mayDrive(
        driver: Driver,
        type: VehicleType,
        transmission: Transmission?,
        @Suppress("UNUSED_PARAMETER") skills: DriverSkillRepository,
        verifySource: Boolean = true,
    ): Boolean {
        if (type !in VehicleCatalog.bookingTypes) return false
        val today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
        if (driver.isFlagged == true || driver.deletedAt != null || driver.verificationStatus == "SUSPENDED" || driver.status == DriverStatus.BLOCKED) {
            return false
        }
        val source = projection
        if (source == null) {
            // Null expiry represents an unlimited-expiry GPLX (e.g., A1/A2/A/B1 Không thời hạn).
            return driver.verificationStatus == "APPROVED" &&
                driver.licenseExpiryDate?.isBefore(today) != true &&
                !(transmission == Transmission.MANUAL && driver.licenseClass == "B1")
        }
        val record = source.current(driver.firebaseUid) ?: return false
        if (record.status != VerificationStatus.APPROVED || record.cccdExpiresOn?.isBefore(today) == true) return false
        if (verifySource && !currentAtSource()) return false
        return coversTrip(record.effectiveGroups(today), type, transmission)
    }

    fun mayDriveForRide(
        driver: Driver, ride: Ride, skills: DriverSkillRepository, verifySource: Boolean = true,
        starting: Boolean = false, now: Instant = Instant.now(),
    ): Boolean {
        if (driver.onlineVehicleType != ride.vehicleType ||
            driver.status != (if (starting) DriverStatus.BUSY else DriverStatus.ONLINE)
        ) return false
        val source = projection
        if (source == null) return mayDrive(driver, ride.vehicleType, ride.transmission, skills)
        if (driver.isFlagged == true || driver.deletedAt != null || driver.verificationStatus == "SUSPENDED") return false
        if (verifySource && !currentAtSource()) return false
        val record = source.current(driver.firebaseUid) ?: return false
        val date = maxOf(now, ride.scheduledAt ?: now).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate()
        val enabled = record.effectiveGroups(date)
        val snapshot = ride.vehicleBookingSnapshot
        val exact = snapshot?.capabilityGroupId
        if (exact == null) {
            if (snapshot != null && snapshot.capabilityStatus != "NEEDS_DETAILS") return false
            return coversTrip(enabled, ride.vehicleType, ride.transmission)
        }
        if (snapshot.catalogVersion != VehicleCatalog.VERSION || snapshot.capabilityStatus != "CUSTOMER_DECLARED") return false
        return exact in enabled && supportedCapabilityGroups.any {
            it.id == exact && it.vehicleType == ride.vehicleType &&
                (it.transmission == null || it.transmission == ride.transmission)
        }
    }

    fun mayGoOnline(driver: Driver, type: VehicleType, skills: DriverSkillRepository): Boolean {
        if (!synchronizedWorkflow) return mayDrive(driver, type, null, skills)
        if (driver.isFlagged == true || driver.deletedAt != null || driver.verificationStatus == "SUSPENDED" || driver.status == DriverStatus.BLOCKED) return false
        val record = projection?.current(driver.firebaseUid) ?: return false
        if (!currentAtSource()) return false
        return coversOnline(record.effectiveGroups(capabilityToday()), type)
    }
}
