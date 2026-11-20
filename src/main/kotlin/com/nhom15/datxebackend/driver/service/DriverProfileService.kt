package com.nhom15.datxebackend.driver.service

import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.driver.dto.DriverIdentityResponse
import com.nhom15.datxebackend.driver.dto.UpdateDriverIdentityRequest
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.driver.dto.DriverProfileResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DriverProfileService(
    private val driverRepository: DriverRepository,
    private val authenticatedUserCache: AuthenticatedUserCache,
) {
    @Transactional(readOnly = true)
    fun get(driverId: Long): DriverProfileResponse = response(getDriver(driverId))

    @Transactional
    fun updateIdentity(driverId: Long, request: UpdateDriverIdentityRequest): DriverIdentityResponse {
        val driver = driverRepository.findByIdForUpdate(driverId).orElseThrow {
            NotFoundException(ErrorCode.USER_NOT_FOUND)
        }
        if (driver.deletedAt != null) throw AppException(ErrorCode.ACCOUNT_LOCKED)
        driver.fullName = request.fullName.trim()
        driver.email = request.email?.trim()?.takeIf(String::isNotEmpty)
        request.bankName?.let { driver.bankName = it.trim().takeIf(String::isNotEmpty) }
        request.bankAccountNumber?.let { driver.bankAccountNumber = it.trim().takeIf(String::isNotEmpty) }
        val firebaseUid = driver.firebaseUid
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                authenticatedUserCache.evict(firebaseUid)
            }
        })
        return DriverIdentityResponse(driver.fullName, driver.email, driver.bankName, driver.bankAccountNumber)
    }

    private fun response(driver: Driver): DriverProfileResponse {
        val id = requireNotNull(driver.id)
        return DriverProfileResponse(
            id = id,
            avatarUrl = driver.avatarUrl,
            licenseNumber = driver.licenseNumber,
            licenseClass = driver.licenseClass,
            yearsOfExperience = driver.yearsOfExperience,
            licenseVerified = driver.licenseVerified == true,
            bankName = driver.bankName,
            bankAccountNumber = driver.bankAccountNumber,
        )
    }

    private fun getDriver(driverId: Long): Driver {
        if (driverId <= 0) throw BadRequestException(message = "driverId phải lớn hơn 0")
        return driverRepository.findById(driverId).orElseThrow { NotFoundException(ErrorCode.USER_NOT_FOUND) }
    }
}
