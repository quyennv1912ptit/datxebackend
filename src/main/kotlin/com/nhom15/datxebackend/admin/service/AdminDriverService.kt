package com.nhom15.datxebackend.admin.service

import com.nhom15.datxebackend.admin.dto.AdminSearchFilter
import com.nhom15.datxebackend.admin.repository.AdminDriverQueryRepository
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.driver.dto.AdminDriverDetail
import com.nhom15.datxebackend.driver.dto.AdminDriverFilter
import com.nhom15.datxebackend.driver.dto.AdminDriverSummary
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.DriverAccountNotificationPayload
import com.nhom15.datxebackend.tracking.service.LocationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun list(actor: AuthUser, filter: AdminDriverFilter): PageResponse<AdminDriverSummary> {
        requireAdmin(actor)
        validatePage(filter.page, filter.size)
        AdminSearchFilter(q = filter.search).validate()
        val verificationStatus = normalizeDriverStatus(filter.verificationStatus)
        val total = queryRepository.count(filter, verificationStatus)
        val content = queryRepository.findPage(filter, verificationStatus)
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
        driver.status = DriverStatus.OFFLINE
        driver.onlineVehicleType = null
        driverRepository.save(driver)
        afterCommit {
            removeFromAvailability(driverId)
            sendNotification(
                driverId,
                DriverAccountNotificationPayload(
                    type = PayloadType.DRIVER_FORCE_OFFLINE,
                    status = "OFFLINE",
                ),
            )
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

    private fun validatePage(page: Int, size: Int) {
        if (page < 0) throw BadRequestException(message = "page phải lớn hơn hoặc bằng 0")
        if (size !in 1..100) throw BadRequestException(message = "size phải nằm trong khoảng 1 đến 100")
    }

    private fun requireAdmin(actor: AuthUser) {
        if (actor.role != Role.ADMIN) throw ForbiddenException(message = "Chỉ admin được quản lý tài xế")
    }
}
