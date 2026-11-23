package com.nhom15.datxebackend.driver.service

import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.driver.dto.DriverIdentityResponse
import com.nhom15.datxebackend.driver.dto.UpdateDriverAvatarRequest
import com.nhom15.datxebackend.driver.dto.UpdateDriverIdentityRequest
import com.nhom15.datxebackend.driver.verification.DriverVerificationWarnings
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.driver.dto.DriverProfileResponse
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DriverProfileService(
    private val driverRepository: DriverRepository,
    private val authenticatedUserCache: AuthenticatedUserCache,
    private val cloudinaryAssetService: CloudinaryAssetService,
    private val jdbc: JdbcTemplate? = null,
) {
    @Transactional(readOnly = true)
    fun get(driverId: Long): DriverProfileResponse = response(getDriver(driverId))

    @Transactional
    fun updateIdentity(driverId: Long, request: UpdateDriverIdentityRequest): DriverIdentityResponse {
        val driver = driverRepository.findByIdForUpdate(driverId).orElseThrow {
            NotFoundException(ErrorCode.USER_NOT_FOUND)
        }
        if (driver.deletedAt != null) throw AppException(ErrorCode.ACCOUNT_LOCKED)
        val requestedName = request.fullName.trim()
        enforceVerifiedNameLock(driver, requestedName)
        driver.fullName = requestedName
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

    @Transactional
    fun updateAvatar(driverId: Long, request: UpdateDriverAvatarRequest): DriverProfileResponse {
        cloudinaryAssetService.validateOwnedImage(
            Role.DRIVER,
            driverId,
            request.secureUrl,
            request.publicId,
        )
        val driver = driverRepository.findByIdForUpdate(driverId).orElseThrow {
            NotFoundException(ErrorCode.USER_NOT_FOUND)
        }
        if (driver.deletedAt != null) throw AppException(ErrorCode.ACCOUNT_LOCKED)
        val oldPublicId = driver.avatarPublicId
        driver.avatarUrl = request.secureUrl.trim()
        driver.avatarPublicId = request.publicId.trim()
        val saved = driverRepository.save(driver)
        if (oldPublicId != saved.avatarPublicId) {
            // The stored value can be a legacy key from before the owner-folder convention.
            // Only structural validation applies here; the new image is still strictly owned.
            cloudinaryAssetService.deleteAfterCommit(oldPublicId)
        }
        return response(saved)
    }

    /**
     * The eKYC-approved name is locked in the verification application, not on the driver row.
     * Without this check, PUT /driver/profile/identity could silently diverge a verified
     * driver's display name from the identity the admin actually approved.
     */
    private fun enforceVerifiedNameLock(driver: Driver, requestedName: String) {
        val db = jdbc ?: return
        if (driver.verificationStatus != "APPROVED") return
        if (driver.fullName?.trim() == requestedName) return
        val ownerUid = driver.firebaseUid
        if (ownerUid.isBlank()) return
        val lockedName = db.query(
            """SELECT locked_full_name FROM driver_verification_applications
               WHERE owner_uid = ? AND locked_full_name IS NOT NULL LIMIT 1""",
            { rs, _ -> rs.getString("locked_full_name") },
            ownerUid,
        ).firstOrNull()?.trim()
        if (lockedName.isNullOrEmpty()) return
        // Diacritic-only differences are the same identity (matches enforceLockedIdentity()).
        if (DriverVerificationWarnings.nameComparison(requestedName, lockedName) == "NAME_MISMATCH") {
            throw AppException(
                ErrorCode.CONFLICT,
                "Họ tên đã được xác minh qua eKYC và không thể tự ý thay đổi.",
            )
        }
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
