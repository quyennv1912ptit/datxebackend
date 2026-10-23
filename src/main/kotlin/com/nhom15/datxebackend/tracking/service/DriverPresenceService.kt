package com.nhom15.datxebackend.tracking.service

import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDate

@Service
class DriverPresenceService(
    private val driverRepository: DriverRepository,
    private val driverSkillRepository: DriverSkillRepository,
    private val locationService: LocationService,
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun setOnline(driverId: Long, online: Boolean, vehicleType: VehicleType? = null): DriverStatus {
        val driver = driverRepository.findByIdForUpdate(driverId).orElseThrow {
            NotFoundException(ErrorCode.USER_NOT_FOUND, "Không tìm thấy tài xế")
        }

        if (online) {
            if (vehicleType == null) {
                throw BadRequestException(message = "Cần chọn vehicle_type khi bật trạng thái online")
            }
            if (driver.verificationStatus != "APPROVED" || driver.isFlagged == true) {
                throw ForbiddenException(
                    ErrorCode.DRIVER_NOT_VERIFIED,
                    "Tài xế chưa đủ điều kiện nhận chuyến",
                )
            }
            if (!driverSkillRepository.hasVerifiedSkill(driverId, vehicleType)) {
                throw ForbiddenException(
                    ErrorCode.DRIVER_NOT_VERIFIED,
                    "Chưa có kỹ năng đã được duyệt cho loại xe $vehicleType",
                )
            }
            if (driver.licenseExpiryDate == null || driver.licenseExpiryDate!!.isBefore(LocalDate.now())) {
                throw ForbiddenException(
                    ErrorCode.DRIVER_NOT_VERIFIED,
                    "Bằng lái chưa được khai báo hoặc đã hết hạn",
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
                runCatching { locationService.markDriverOnline(driverId) }
                    .onFailure { log.warn("Không cập nhật được Redis khi tài xế {} bật online", driverId, it) }
            }
            return driver.status
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
            runCatching { locationService.removeDriver(driverId) }
                .onFailure { log.warn("Không xóa được tài xế {} khỏi Redis khi tắt online", driverId, it) }
        }
        return driver.status
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
