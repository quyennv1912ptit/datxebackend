package com.nhom15.datxebackend.tracking.service

import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.tracking.dto.DriverAvailabilityResponse
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility

@Service
class DriverPresenceService(
    private val driverRepository: DriverRepository,
    private val locationService: LocationService,
    private val redisTemplate: StringRedisTemplate,
    private val wallets: com.nhom15.datxebackend.payment.service.WalletService,
    private val verification: DriverVerificationEligibility,
    private val authenticatedUserCache: AuthenticatedUserCache? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun setOnline(driverId: Long, online: Boolean, vehicleType: VehicleType? = null): DriverStatus =
        updateAvailability(driverId, online, vehicleType).status

    @Transactional
    fun updateAvailability(
        driverId: Long,
        online: Boolean,
        vehicleType: VehicleType? = null,
    ): DriverAvailabilityResponse {
        val driver = driverRepository.findByIdForUpdate(driverId).orElseThrow {
            NotFoundException(ErrorCode.USER_NOT_FOUND, "Không tìm thấy tài xế")
        }

        if (driver.status == DriverStatus.BLOCKED) {
            // The transaction will roll back, so Redis cleanup must happen now (not afterCommit).
            runCatching { locationService.removeDriver(driverId) }
                .onFailure { log.warn("Không xóa được tài xế bị khóa {} khỏi Redis", driverId, it) }
            throw ForbiddenException(ErrorCode.ACCOUNT_LOCKED, "Tài khoản tài xế đã bị khóa")
        }
        val firebaseUid = driver.firebaseUid
        if (online) {
            wallets.requireEligibleDriver(driverId)
            if (vehicleType == null) {
                throw BadRequestException(message = "Cần chọn vehicle_type khi bật trạng thái online")
            }
            if (!verification.mayGoOnline(driver, vehicleType)) {
                throw ForbiddenException(
                    ErrorCode.DRIVER_NOT_VERIFIED,
                    "Tài xế chưa đủ điều kiện nhận chuyến",
                )
            }
            val id = driverId.toString()
            val hasOffer = redisTemplate.hasKey(RedisKeys.driverLock(id)) == true
            val hasActiveRide = redisTemplate.hasKey(RedisKeys.driverCurrentRide(id)) == true
            if (driver.status == DriverStatus.BUSY || hasOffer || hasActiveRide) {
                throw ConflictException(ErrorCode.DRIVER_NOT_AVAILABLE)
            }

            driver.status = DriverStatus.ONLINE
            driver.onlineVehicleType = vehicleType
            driverRepository.save(driver)
            afterCommit {
                authenticatedUserCache?.evict(firebaseUid)
                runCatching { locationService.markDriverOnline(driverId) }
                    .onFailure { log.warn("Không cập nhật được Redis khi tài xế {} bật online", driverId, it) }
            }
            return DriverAvailabilityResponse(driver.status, driver.onlineVehicleType)
        }

        val id = driverId.toString()
        val hasOffer = redisTemplate.hasKey(RedisKeys.driverLock(id)) == true
        val hasActiveRide = redisTemplate.hasKey(RedisKeys.driverCurrentRide(id)) == true
        if (hasOffer || hasActiveRide || driver.status == DriverStatus.BUSY) {
            throw ConflictException(ErrorCode.DRIVER_NOT_AVAILABLE)
        }

        driver.status = DriverStatus.OFFLINE
        driver.onlineVehicleType = null
        driverRepository.save(driver)
        afterCommit {
            authenticatedUserCache?.evict(firebaseUid)
            runCatching { locationService.removeDriver(driverId) }
                .onFailure { log.warn("Không xóa được tài xế {} khỏi Redis khi tắt online", driverId, it) }
        }
        return DriverAvailabilityResponse(driver.status, driver.onlineVehicleType)
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }
}
