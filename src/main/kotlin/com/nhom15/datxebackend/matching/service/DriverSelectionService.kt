package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.driver.entity.Driver
import org.springframework.stereotype.Service
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.matching.dto.RankedDriverDTO
import com.nhom15.datxebackend.common.port.NearbyDriver
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import java.time.LocalDate

@Service
class DriverSelectionService(
    private val driverRepository: DriverRepository,
    private val driverSkillRepository: DriverSkillRepository,
) {
    fun filterAndRankDrivers(
        nearbyDrivers: List<NearbyDriver>,
        requiredVehicleType: VehicleType,
        requiredTransmission: Transmission,
        radiusKm: Double,
    ): List<RankedDriverDTO> {
        if (nearbyDrivers.isEmpty()) return emptyList()
        require(radiusKm > 0.0) { "radiusKm phải lớn hơn 0" }

        // Tạo map id -> driver
        val driversById: Map<Long, Driver> = driverRepository
            .findAllById(nearbyDrivers.map { it.driverId })
            .associateBy { it.id }
        val verifiedDriverIds = driverSkillRepository.findVerifiedDriverIds(
            nearbyDrivers.map { it.driverId }.distinct(),
            requiredVehicleType,
            requiredTransmission,
        )

        return nearbyDrivers
            .mapNotNull { nearby ->
                val driver = driversById[nearby.driverId] ?: return@mapNotNull null
                if (nearby.driverId !in verifiedDriverIds || !isEligible(driver, requiredVehicleType, requiredTransmission)) {
                    return@mapNotNull null
                }

                RankedDriverDTO(
                    driverId = nearby.driverId,
                    distance = nearby.distanceKm,
                    score = calculateScore(driver, nearby.distanceKm, radiusKm)
                )
            }
            .sortedByDescending { it.score }
    }

    fun isEligible(driver: Driver, requiredVehicleType: VehicleType, requiredTransmission: Transmission): Boolean {
        if (driver.status != DriverStatus.ONLINE) return false
        if (driver.onlineVehicleType != requiredVehicleType) return false
        if (driver.verificationStatus != "APPROVED" || driver.isFlagged == true) return false
        if (driver.licenseExpiryDate == null || driver.licenseExpiryDate!!.isBefore(LocalDate.now())) return false

        if (requiredTransmission == Transmission.MANUAL && driver.licenseClass == "B1") return false

        return true
    }

    private fun calculateScore(driver: Driver, distanceKm: Double, radiusKm: Double): Double {
        val rating = driver.averageRating
        val experience = driver.yearsOfExperience ?: 0

        var score = 0.0

        score += (rating ?: 5.0) * 10.0

        score += (experience * 2.0).coerceAtMost(10.0)

        // Giữ ảnh hưởng của rating khi tìm ở các vòng bán kính lớn hơn.
        score -= (distanceKm / radiusKm).coerceIn(0.0, 1.0) * 10.0

        return score
    }
}
