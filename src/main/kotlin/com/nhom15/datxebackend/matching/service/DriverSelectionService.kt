package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.util.GeoUtils
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.config.properties.DriverRanking
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.matching.dto.RankedDriverDTO
import com.nhom15.datxebackend.common.port.NearbyDriver
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility

@Service
class DriverSelectionService(
    private val driverRepository: DriverRepository,
    private val driverSkillRepository: DriverSkillRepository,
    private val properties: MatchingProperties,
    @org.springframework.beans.factory.annotation.Value("\${wallet.minimum-credit:50000}") private val minimumCredit: Long = 50_000,
    private val verification: DriverVerificationEligibility = DriverVerificationEligibility(),
    private val redis: StringRedisTemplate? = null,
    private val rides: RideRepository? = null,
) {
    fun filterAndRankDrivers(
        nearbyDrivers: List<NearbyDriver>,
        requiredVehicleType: VehicleType,
        requiredTransmission: Transmission,
        radiusKm: Double,
        ride: Ride? = null,
    ): List<RankedDriverDTO> {
        if (nearbyDrivers.isEmpty()) return emptyList()
        require(radiusKm > 0.0) { "radiusKm phải lớn hơn 0" }
        if (!verification.currentAtSource()) return emptyList()

        // Tạo map id -> driver
        val driversById: Map<Long, Driver> = driverRepository
            .findAllById(nearbyDrivers.map { it.driverId })
            .associateBy { it.id }
        val verifiedDriverIds = if (verification.synchronizedWorkflow) driversById.keys else driverSkillRepository.findVerifiedDriverIds(
            nearbyDrivers.map { it.driverId }.distinct(),
            requiredVehicleType,
        )

        return nearbyDrivers
            .mapNotNull { nearby ->
                val driver = driversById[nearby.driverId] ?: return@mapNotNull null
                if (nearby.driverId !in verifiedDriverIds || !nearby.distanceKm.isFinite() ||
                    nearby.distanceKm !in 0.0..radiusKm || (driver.creditBalance ?: 0) < minimumCredit ||
                    !(if (ride == null) isEligible(driver, requiredVehicleType, requiredTransmission, verifySource = false)
                    else verification.mayDriveForRide(driver, ride, driverSkillRepository, verifySource = false))) {
                    return@mapNotNull null
                }

                RankedDriverDTO(
                    driverId = nearby.driverId,
                    distance = nearby.distanceKm,
                    score = calculateScore(driver, nearby.distanceKm, radiusKm)
                )
            }
            .let { drivers ->
                // The standard flow asks the nearest eligible driver first. The score
                // mode remains available for experiments through configuration.
                if (properties.rankingMode == DriverRanking.DISTANCE) drivers.sortedBy { it.distance }
                else drivers.sortedByDescending { it.score }
            }
    }

    fun isEligible(
        driver: Driver, requiredVehicleType: VehicleType, requiredTransmission: Transmission,
        verifySource: Boolean = true,
    ): Boolean {
        if (driver.status != DriverStatus.ONLINE) return false
        if ((driver.creditBalance ?: 0) < minimumCredit) return false
        if (driver.onlineVehicleType != requiredVehicleType) return false
        return verification.mayDrive(driver, requiredVehicleType, requiredTransmission, driverSkillRepository, verifySource)
    }

    @Transactional
    fun recordOfferIfEligible(ride: Ride, driverId: Long, record: () -> Boolean): Boolean {
        val current = rides?.findByIdForUpdate(ride.id)?.orElse(null) ?: return false
        if (current.status != RideStatus.SEARCHING || current.redispatchCount != ride.redispatchCount) return false
        val driver = driverRepository.findByIdForUpdate(driverId).orElse(null) ?: return false
        if ((driver.creditBalance ?: 0) < minimumCredit ||
            !verification.mayDriveForRide(driver, current, driverSkillRepository) ||
            (!verification.synchronizedWorkflow && !driverSkillRepository.hasVerifiedSkill(driverId, ride.vehicleType))
        ) return false
        if (verification.synchronizedWorkflow) {
            val position = redis?.opsForHash<String, String>()?.entries(RedisKeys.driverLocation(driverId.toString()))
                ?: return false
            val lat = position["lat"]?.toDoubleOrNull() ?: return false
            val lng = position["lng"]?.toDoubleOrNull() ?: return false
            val point = GeoPoint(lat, lng)
            if (!GeoUtils.isValid(point) || GeoUtils.distanceKm(point, ride.pickup.geoPoint) > properties.searchRadiiKm.last()) {
                return false
            }
        }
        return record()
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
