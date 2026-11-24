package com.nhom15.datxebackend.admin.service

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.nhom15.datxebackend.admin.dto.AdminSearchFilter
import com.nhom15.datxebackend.admin.repository.AdminDriverQueryRepository
import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.driver.dto.AdminDriverDetail
import com.nhom15.datxebackend.driver.dto.AdminDriverFilter
import com.nhom15.datxebackend.driver.dto.AdminDriverSummary
import com.nhom15.datxebackend.driver.dto.UpdateDriverAccountStatusRequest
import com.nhom15.datxebackend.driver.dto.UpdateDriverWithdrawalFreezeRequest
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.DriverAccountNotificationPayload
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.tracking.service.LocationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class AdminDriverService @Autowired constructor(
    private val queryRepository: AdminDriverQueryRepository,
    private val skillRepository: DriverSkillRepository,
    private val driverRepository: DriverRepository,
    private val locationService: LocationService,
    private val notificationPort: NotificationPort,
    private val userCache: AuthenticatedUserCache? = null,
    private val rideRepository: RideRepository? = null,
    private val firebaseAuth: FirebaseAuth? = null,
    private val jdbcTemplate: JdbcTemplate? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun list(actor: AuthUser, filter: AdminDriverFilter): PageResponse<AdminDriverSummary> {
        requireAdmin(actor)
        validatePage(filter.page, filter.size)
        AdminSearchFilter(q = filter.search).validate()
        val verificationStatus = normalizeDriverStatus(filter.verificationStatus)
        val normalizedAccountStatus = normalizeAccountStatus(filter.accountStatus, required = false)
        val normalizedFilter = filter.copy(accountStatus = normalizedAccountStatus)
        val total = queryRepository.count(normalizedFilter, verificationStatus)
        val content = queryRepository.findPage(normalizedFilter, verificationStatus)
        val totalPages = if (total == 0L) 0 else ((total + filter.size - 1) / filter.size).toInt()
        return PageResponse(
            content = content,
            page = filter.page,
            size = filter.size,
            totalElements = total,
            totalPages = totalPages,
            hasNext = filter.page + 1 < totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun detail(actor: AuthUser, driverId: Long): AdminDriverDetail {
        requireAdmin(actor)
        val driver = queryRepository.findById(driverId)
            ?: throw NotFoundException(ErrorCode.USER_NOT_FOUND, "Không tìm thấy tài xế")
        return AdminDriverDetail(
            driver = driver,
            verification = queryRepository.findVerification(driverId),
            skills = skillRepository.findAllForDriver(driverId),
        )
    }

    @Transactional
    fun forceOffline(actor: AuthUser, driverId: Long) {
        requireAdmin(actor)
        val driver = lockDriver(driverId)
        if (driver.status == DriverStatus.BUSY) {
            throw ConflictException(ErrorCode.INVALID_RIDE_STATE, "Không thể tắt kết nối khi tài xế đang thực hiện chuyến đi")
        }
        if (driver.status != DriverStatus.BLOCKED) {
            driver.status = DriverStatus.OFFLINE
        }
        driver.onlineVehicleType = null
        driverRepository.save(driver)
        userCache?.evictAfterCommit(driver.firebaseUid)
        afterCommit {
            removeFromAvailability(driverId)
            sendNotification(
                driverId,
                DriverAccountNotificationPayload(
                    type = PayloadType.DRIVER_FORCE_OFFLINE,
                    status = driver.status.name,
                ),
            )
        }
    }

    @Transactional
    fun updateAccountStatus(
        actor: AuthUser,
        driverId: Long,
        request: UpdateDriverAccountStatusRequest,
    ): AdminDriverDetail {
        requireAdmin(actor)
        val nextStatus = normalizeAccountStatus(request.accountStatus, required = true)!!
        val trimmedReason = request.reason?.trim()?.takeIf(String::isNotEmpty)
        if (nextStatus != ACCOUNT_ACTIVE && trimmedReason == null) {
            throw BadRequestException(message = "Cần nhập lý do khi tạm ngưng hoặc khóa tài xế")
        }
        if (trimmedReason != null && trimmedReason.length > 1000) {
            throw BadRequestException(message = "Lý do không được vượt quá 1000 ký tự")
        }

        val driver = lockDriver(driverId)
        val previousStatus = driver.accountStatus
        val hasActiveRide = driver.status == DriverStatus.BUSY ||
            (rideRepository?.existsByDriverIdAndStatusIn(driverId, ACTIVE_RIDE_STATUSES) == true)

        driver.accountStatus = nextStatus
        driver.accountStatusReason = if (nextStatus == ACCOUNT_ACTIVE) null else trimmedReason
        request.withdrawalFrozen?.let { driver.withdrawalFrozen = it }

        val forcedOfflineNow = nextStatus in setOf(ACCOUNT_SUSPENDED, ACCOUNT_BANNED) && !hasActiveRide
        if (forcedOfflineNow) {
            if (driver.status != DriverStatus.BLOCKED) {
                driver.status = DriverStatus.OFFLINE
            }
            driver.onlineVehicleType = null
        }

        driverRepository.save(driver)
        recordDriverAction(
            driverId = driverId,
            adminId = actor.id,
            actionType = "ACCOUNT_STATUS_CHANGED",
            previousStatus = previousStatus,
            newStatus = nextStatus,
            withdrawalFrozen = driver.withdrawalFrozen,
            reason = trimmedReason,
        )
        userCache?.evictAfterCommit(driver.firebaseUid)

        val firebaseUid = driver.firebaseUid
        val finalDriverStatus = driver.status.name
        val finalWithdrawalFrozen = driver.withdrawalFrozen
        afterCommit {
            if (forcedOfflineNow) {
                removeFromAvailability(driverId)
                sendNotification(
                    driverId,
                    DriverAccountNotificationPayload(
                        type = if (nextStatus == ACCOUNT_BANNED) {
                            PayloadType.DRIVER_ACCOUNT_STATUS_UPDATED
                        } else {
                            PayloadType.DRIVER_FORCE_OFFLINE
                        },
                        status = finalDriverStatus,
                        reason = trimmedReason,
                        accountStatus = nextStatus,
                        withdrawalFrozen = finalWithdrawalFrozen,
                    ),
                )
                if (nextStatus == ACCOUNT_BANNED) {
                    revokeFirebaseTokens(driverId, firebaseUid)
                }
            } else if (nextStatus == ACCOUNT_ACTIVE) {
                sendNotification(
                    driverId,
                    DriverAccountNotificationPayload(
                        type = PayloadType.DRIVER_ACCOUNT_STATUS_UPDATED,
                        status = finalDriverStatus,
                        reason = trimmedReason,
                        accountStatus = nextStatus,
                        withdrawalFrozen = finalWithdrawalFrozen,
                    ),
                )
            }
        }

        return detail(actor, driverId)
    }

    @Transactional
    fun updateWithdrawalFreeze(
        actor: AuthUser,
        driverId: Long,
        request: UpdateDriverWithdrawalFreezeRequest,
    ): AdminDriverDetail {
        requireAdmin(actor)
        val trimmedReason = request.reason?.trim()?.takeIf(String::isNotEmpty)
        if (request.withdrawalFrozen && trimmedReason == null) {
            throw BadRequestException(message = "Cần nhập lý do khi khóa rút tiền của tài xế")
        }
        if (trimmedReason != null && trimmedReason.length > 1000) {
            throw BadRequestException(message = "Lý do không được vượt quá 1000 ký tự")
        }

        val driver = lockDriver(driverId)
        driver.withdrawalFrozen = request.withdrawalFrozen
        driverRepository.save(driver)
        recordDriverAction(
            driverId = driverId,
            adminId = actor.id,
            actionType = if (request.withdrawalFrozen) "WITHDRAWAL_FROZEN" else "WITHDRAWAL_UNFROZEN",
            previousStatus = driver.accountStatus,
            newStatus = driver.accountStatus,
            withdrawalFrozen = driver.withdrawalFrozen,
            reason = trimmedReason,
        )
        userCache?.evictAfterCommit(driver.firebaseUid)

        val finalDriverStatus = driver.status.name
        val finalAccountStatus = driver.accountStatus
        val finalWithdrawalFrozen = driver.withdrawalFrozen
        afterCommit {
            sendNotification(
                driverId,
                DriverAccountNotificationPayload(
                    type = PayloadType.DRIVER_ACCOUNT_STATUS_UPDATED,
                    status = finalDriverStatus,
                    reason = trimmedReason,
                    accountStatus = finalAccountStatus,
                    withdrawalFrozen = finalWithdrawalFrozen,
                ),
            )
        }

        return detail(actor, driverId)
    }

    private fun recordDriverAction(
        driverId: Long,
        adminId: Long,
        actionType: String,
        previousStatus: String?,
        newStatus: String?,
        withdrawalFrozen: Boolean?,
        reason: String?,
    ) {
        jdbcTemplate?.update(
            """INSERT INTO admin_driver_actions
               (driver_id, admin_id, action_type, previous_status, new_status, withdrawal_frozen, reason)
               VALUES (?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
            driverId,
            adminId,
            actionType,
            previousStatus,
            newStatus,
            withdrawalFrozen,
            reason,
        )
    }

    private fun revokeFirebaseTokens(driverId: Long, firebaseUid: String) {
        try {
            firebaseAuth?.revokeRefreshTokens(firebaseUid)
        } catch (e: FirebaseAuthException) {
            log.warn("Không thu hồi được phiên Firebase cho tài xế {}", driverId, e)
        } catch (e: RuntimeException) {
            log.warn("Lỗi thu hồi phiên Firebase cho tài xế {}", driverId, e)
        }
    }

    private fun sendNotification(driverId: Long, payload: DriverAccountNotificationPayload) {
        try {
            notificationPort.send(Recipient.Driver(driverId), payload)
        } catch (e: Exception) {
            log.warn("Không gửi được thông báo quản trị cho tài xế {}", driverId, e)
        }
    }

    private fun removeFromAvailability(driverId: Long) {
        try {
            locationService.removeDriver(driverId)
        } catch (e: Exception) {
            log.error("Không xóa được tài xế {} khỏi Redis sau quyết định quản trị", driverId, e)
        }
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

    private fun lockDriver(driverId: Long): Driver = driverRepository.findByIdForUpdate(driverId)
        .orElseThrow { NotFoundException(ErrorCode.USER_NOT_FOUND, "Không tìm thấy tài xế") }

    private fun normalizeDriverStatus(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val status = raw.trim().uppercase()
        if (status !in setOf("PENDING", "APPROVED", "REJECTED", "SUSPENDED")) {
            throw BadRequestException(message = "verification_status không hợp lệ")
        }
        return status
    }

    private fun normalizeAccountStatus(raw: String?, required: Boolean): String? {
        if (raw.isNullOrBlank()) {
            if (required) throw BadRequestException(message = "account_status không được để trống")
            return null
        }
        val status = raw.trim().uppercase()
        if (status !in setOf(ACCOUNT_ACTIVE, ACCOUNT_SUSPENDED, ACCOUNT_BANNED)) {
            throw BadRequestException(message = "account_status phải là ACTIVE, SUSPENDED hoặc BANNED")
        }
        return status
    }

    private fun validatePage(page: Int, size: Int) {
        if (page < 0) throw BadRequestException(message = "page phải lớn hơn hoặc bằng 0")
        if (size !in 1..100) throw BadRequestException(message = "size phải nằm trong khoảng 1 đến 100")
    }

    private fun requireAdmin(actor: AuthUser) {
        if (actor.role != Role.ADMIN) throw ForbiddenException(message = "Chỉ admin được quản lý tài xế")
    }

    private companion object {
        const val ACCOUNT_ACTIVE = "ACTIVE"
        const val ACCOUNT_SUSPENDED = "SUSPENDED"
        const val ACCOUNT_BANNED = "BANNED"
    }
}
