package com.nhom15.datxebackend.driver.service

import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.driver.dto.DriverProfileResponse
import com.nhom15.datxebackend.driver.dto.UpdateDriverProfileRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DriverProfileService(
    private val driverRepository: DriverRepository,
) {
    @Transactional(readOnly = true)
    fun get(driverId: Long): DriverProfileResponse = response(getDriver(driverId))

    @Transactional
    fun update(driverId: Long, request: UpdateDriverProfileRequest): DriverProfileResponse {
        val driver = getDriver(driverId)
        driver.licenseNumber = request.licenseNumber.trim()
        driver.licenseClass = request.licenseClass.trim().uppercase()
        driver.yearsOfExperience = request.yearsOfExperience
        request.licenseImageUrl?.let { imageUrl ->
            val url = imageUrl.trim()
            if (!(url.startsWith("https://") || url.startsWith("http://"))) {
                throw BadRequestException(message = "Đường dẫn ảnh bằng lái không hợp lệ")
            }
            driver.licenseImageUrl = url
        }
        val saved = driverRepository.save(driver)
        return response(saved)
    }

    @Transactional
    fun review(driverId: Long, approved: Boolean): DriverProfileResponse {
        val driver = getDriver(driverId)
        if (approved && (driver.licenseNumber.isNullOrBlank() || driver.licenseClass.isNullOrBlank())) {
            throw BadRequestException(message = "Tài xế chưa khai báo đủ thông tin bằng lái")
        }
        driver.licenseVerified = approved
        return response(driverRepository.save(driver))
    }

    private fun response(driver: Driver): DriverProfileResponse {
        val id = requireNotNull(driver.id)
        val complete = !driver.licenseNumber.isNullOrBlank() && !driver.licenseClass.isNullOrBlank()
        return DriverProfileResponse(
            id = id,
            licenseNumber = driver.licenseNumber,
            licenseClass = driver.licenseClass,
            yearsOfExperience = driver.yearsOfExperience,
            licenseImageUrl = driver.licenseImageUrl,
            licenseVerified = driver.licenseVerified == true,
            licenseReviewStatus = when {
                driver.licenseVerified == true -> "APPROVED"
                complete -> "PENDING"
                else -> "NOT_SUBMITTED"
            },
        )
    }

    private fun getDriver(driverId: Long): Driver {
        if (driverId <= 0) throw BadRequestException(message = "driverId phải lớn hơn 0")
        return driverRepository.findById(driverId).orElseThrow { NotFoundException(ErrorCode.USER_NOT_FOUND) }
    }
}
