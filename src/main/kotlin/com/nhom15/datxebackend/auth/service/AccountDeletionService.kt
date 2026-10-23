package com.nhom15.datxebackend.auth.service

import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.UserStatus
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.notification.token.DeviceTokenRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID

@Service
class AccountDeletionService(
    private val customerRepository: CustomerRepository,
    private val driverRepository: DriverRepository,
    private val deviceTokenRepository: DeviceTokenRepository,
    private val rideRepository: RideRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val cleanupOutbox: AccountCleanupOutboxRepository,
    private val authenticatedUserCache: AuthenticatedUserCache,
) {
    @Transactional
    fun deleteAccount(user: AuthUser) {
        val terminalStatuses = RideStatus.entries.filter { it.terminal }
        when (user.role) {
            Role.CUSTOMER -> deleteCustomer(user.id, terminalStatuses)
            Role.DRIVER -> deleteDriver(user.id, terminalStatuses)
            Role.ADMIN -> throw ForbiddenException(
                message = "Không thể xóa tài khoản quản trị qua API tự phục vụ",
            )
        }
    }

    private fun deleteCustomer(customerId: Long, terminalStatuses: List<RideStatus>) {
        val customer = customerRepository.findById(customerId).orElseThrow {
            AppException(ErrorCode.USER_NOT_FOUND)
        }
        if (customer.deletedAt != null) throw AppException(ErrorCode.ACCOUNT_LOCKED)
        if (rideRepository.existsByCustomerIdAndStatusNotIn(customerId, terminalStatuses)) {
            throw ConflictException(ErrorCode.ACCOUNT_HAS_ACTIVE_RIDES)
        }

        val firebaseUid = customer.firebaseUid
        val avatarPublicId = customer.avatarPublicId
        val vehicleImagePublicIds = jdbcTemplate.query(
            """SELECT vi.cloudinary_public_id FROM vehicle_images vi
               JOIN vehicles v ON v.id = vi.vehicle_id
               WHERE v.customer_id = ?""".trimIndent(),
            { row, _ -> row.getString("cloudinary_public_id") },
            customerId,
        )
        customer.deletedAt = Instant.now()
        customer.firebaseUid = tombstoneUid("customer", customerId)
        customer.status = UserStatus.INACTIVE
        customer.anonymize()
        customerRepository.saveAndFlush(customer)
        evictAuthCacheAfterCommit(firebaseUid)

        deviceTokenRepository.deleteAllByRoleAndOwnerId(Role.CUSTOMER, customerId)
        jdbcTemplate.update("DELETE FROM notifications WHERE recipient_customer_id = ?", customerId)
        jdbcTemplate.update(
            "DELETE FROM vehicle_images WHERE vehicle_id IN (SELECT id FROM vehicles WHERE customer_id = ?)",
            customerId,
        )
        jdbcTemplate.update(
            "UPDATE rides SET license_plate = NULL WHERE vehicle_id IN (SELECT id FROM vehicles WHERE customer_id = ?)",
            customerId,
        )
        jdbcTemplate.update(
            """UPDATE vehicles
               SET plate_number = 'deleted-' || substr(md5(id::text), 1, 12),
                   brand_model = NULL, brand = NULL, model = NULL, color = NULL
               WHERE customer_id = ?""".trimIndent(),
            customerId,
        )
        cleanupOutbox.enqueue(
            firebaseUid = firebaseUid,
            ownerRole = Role.CUSTOMER.name,
            ownerId = customerId,
            publicIds = listOfNotNull(avatarPublicId) + vehicleImagePublicIds,
        )
    }

    private fun deleteDriver(driverId: Long, terminalStatuses: List<RideStatus>) {
        val driver = driverRepository.findById(driverId).orElseThrow {
            AppException(ErrorCode.USER_NOT_FOUND)
        }
        if (driver.deletedAt != null) throw AppException(ErrorCode.ACCOUNT_LOCKED)
        if (rideRepository.existsByDriverIdAndStatusNotIn(driverId, terminalStatuses)) {
            throw ConflictException(ErrorCode.ACCOUNT_HAS_ACTIVE_RIDES)
        }

        val firebaseUid = driver.firebaseUid
        val avatarPublicId = driver.avatarPublicId
        val documentPublicIds = jdbcTemplate.query(
            "SELECT cloudinary_public_id FROM driver_documents WHERE driver_id = ?",
            { row, _ -> row.getString("cloudinary_public_id") },
            driverId,
        )
        driver.deletedAt = Instant.now()
        driver.firebaseUid = tombstoneUid("driver", driverId)
        driver.fullName = null
        driver.email = null
        driver.phoneNumber = tombstoneUid("driver-phone", driverId)
        driver.avatarUrl = null
        driver.avatarPublicId = null
        driver.bankAccountNumber = null
        driver.licenseClass = null
        driver.licenseNumber = null
        driver.licenseImageUrl = null
        driver.licenseExpiryDate = null
        driver.licenseVerified = false
        driver.backgroundCheckStatus = null
        driver.lastLocationUpdate = null
        driver.status = DriverStatus.OFFLINE
        driver.onlineVehicleType = null
        driver.verificationStatus = SUSPENDED_STATUS
        driverRepository.saveAndFlush(driver)
        evictAuthCacheAfterCommit(firebaseUid)

        deviceTokenRepository.deleteAllByRoleAndOwnerId(Role.DRIVER, driverId)
        jdbcTemplate.update("DELETE FROM notifications WHERE recipient_driver_id = ?", driverId)
        jdbcTemplate.update("DELETE FROM driver_documents WHERE driver_id = ?", driverId)
        jdbcTemplate.update("DELETE FROM driver_vehicle_skills WHERE driver_id = ?", driverId)
        cleanupOutbox.enqueue(
            firebaseUid = firebaseUid,
            ownerRole = Role.DRIVER.name,
            ownerId = driverId,
            publicIds = listOfNotNull(avatarPublicId) + documentPublicIds,
        )
    }

    private fun Customer.anonymize() {
        fullName = null
        email = null
        phoneNumber = null
        avatarUrl = null
        avatarPublicId = null
        defaultPaymentMethod = null
        dateOfBirth = null
        gender = null
        lastLoginAt = null
    }

    private fun evictAuthCacheAfterCommit(firebaseUid: String) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                authenticatedUserCache.evict(firebaseUid)
            }
        })
    }

    private fun tombstoneUid(role: String, id: Long): String = "deleted-$role-$id-${UUID.randomUUID()}"

    private companion object {
        const val SUSPENDED_STATUS = "SUSPENDED"
    }
}
