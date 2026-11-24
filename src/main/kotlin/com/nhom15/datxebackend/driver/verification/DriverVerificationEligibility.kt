package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.vehicle.catalog.VehicleCatalog
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Service
class DriverVerificationEligibility @Autowired constructor(
    private val projection: VerificationProjectionRepository,
    private val sync: VerificationSync? = null,
) {
    fun currentAtSource(): Boolean {
        // When external HTTP verification sync is off, PostgreSQL projection is the direct single source of truth.
        val externalSync = sync ?: return true
        val local = projection.synchronizedHead() ?: return false
        return externalSync.confirms(local)
    }

    fun displayedStatus(driver: Driver): String {
        if (driver.verificationStatus == "SUSPENDED") return "SUSPENDED"
        return when (projection.current(driver.firebaseUid)?.status) {
            VerificationStatus.APPROVED -> "APPROVED"
            VerificationStatus.REJECTED, VerificationStatus.REVOKED -> "REJECTED"
            else -> "PENDING"
        }
    }

    fun statusAfterRide(driver: Driver): DriverStatus {
        if (driver.status == DriverStatus.BLOCKED) {
            driver.onlineVehicleType = null
            return DriverStatus.BLOCKED
        }
        val onlineType = driver.onlineVehicleType
        if (onlineType != null && mayGoOnline(driver, onlineType)) {
            return DriverStatus.ONLINE
        }
        driver.onlineVehicleType = null
        return DriverStatus.OFFLINE
    }

    fun mayDrive(
        driver: Driver,
        type: VehicleType,
        transmission: Transmission?,
        verifySource: Boolean = true,
    ): Boolean {
        if (type !in VehicleCatalog.bookingTypes) return false
        val today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
        if (driver.isFlagged == true || driver.deletedAt != null || driver.verificationStatus == "SUSPENDED" || driver.accountStatus != "ACTIVE" || driver.status == DriverStatus.BLOCKED) {
            return false
        }
        val record = projection.current(driver.firebaseUid) ?: return false
        if (record.status != VerificationStatus.APPROVED || record.cccdExpiresOn?.isBefore(today) == true) return false
        if (verifySource && !currentAtSource()) return false
        return coversTrip(record.effectiveGroups(today), type, transmission)
    }

    fun mayDriveForRide(
        driver: Driver, ride: Ride, verifySource: Boolean = true,
        starting: Boolean = false, now: Instant = Instant.now(),
    ): Boolean {
        if (driver.onlineVehicleType != ride.vehicleType ||
            driver.status != (if (starting) DriverStatus.BUSY else DriverStatus.ONLINE)
        ) return false
        if (driver.isFlagged == true || driver.deletedAt != null || driver.verificationStatus == "SUSPENDED" || (!starting && driver.accountStatus != "ACTIVE")) return false
        if (verifySource && !currentAtSource()) return false
        val record = projection.current(driver.firebaseUid) ?: return false
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

    fun mayGoOnline(driver: Driver, type: VehicleType): Boolean {
        if (driver.isFlagged == true || driver.deletedAt != null || driver.verificationStatus == "SUSPENDED" || driver.accountStatus != "ACTIVE" || driver.status == DriverStatus.BLOCKED) return false
        val record = projection.current(driver.firebaseUid) ?: return false
        if (!currentAtSource()) return false
        return coversOnline(record.effectiveGroups(capabilityToday()), type)
    }
}
