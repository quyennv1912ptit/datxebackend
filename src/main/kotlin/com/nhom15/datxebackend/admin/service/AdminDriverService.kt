package com.nhom15.datxebackend.admin.service

import com.nhom15.datxebackend.admin.repository.AdminDriverQueryRepository
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
import com.nhom15.datxebackend.driver.dto.AdminDriverDocumentQueueItem
import com.nhom15.datxebackend.driver.dto.AdminDriverFilter
import com.nhom15.datxebackend.driver.dto.AdminDriverSummary
import com.nhom15.datxebackend.driver.dto.DocumentVerificationStatus
import com.nhom15.datxebackend.driver.dto.DriverDocumentResponse
import com.nhom15.datxebackend.driver.dto.DriverVerificationStatus
import com.nhom15.datxebackend.driver.dto.ReviewDriverDocumentRequest
import com.nhom15.datxebackend.driver.dto.SetDriverVerificationStatusRequest
import com.nhom15.datxebackend.driver.dto.VerifyDriverSkillRequest
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverDocumentRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.DriverAccountNotificationPayload
import com.nhom15.datxebackend.tracking.service.LocationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

@Service
class AdminDriverService(
    private val queryRepository: AdminDriverQueryRepository,
    private val documentRepository: DriverDocumentRepository,
    private val skillRepository: DriverSkillRepository,
    private val driverRepository: DriverRepository,
    private val locationService: LocationService,
    private val notificationPort: NotificationPort,
    @Value("\${app.driver.required-document-types:CCCD,DRIVER_LICENSE}") requiredDocumentTypes: String,
    @Value("\${app.driver.license-document-type:DRIVER_LICENSE}") licenseDocumentType: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val requiredDocumentTypes = requiredDocumentTypes.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::uppercase)
        .distinct()
    private val licenseDocumentType = licenseDocumentType.trim().uppercase()

    init {
        require(this.requiredDocumentTypes.isNotEmpty()) {
            "app.driver.required-document-types phải có ít nhất một loại giấy tờ"
        }
        require(this.licenseDocumentType.isNotBlank()) {
            "app.driver.license-document-type không được để trống"
        }
    }

    @Transactional(readOnly = true)
    fun list(actor: AuthUser, filter: AdminDriverFilter): PageResponse<AdminDriverSummary> {
        requireAdmin(actor)
        validatePage(filter.page, filter.size)
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
            documents = documentRepository.findAllForDriver(driverId),
            skills = skillRepository.findAllForDriver(driverId),
        )
    }

    @Transactional(readOnly = true)
    fun documentQueue(
        actor: AuthUser,
        status: DocumentVerificationStatus,
        page: Int,
        size: Int,
    ): PageResponse<AdminDriverDocumentQueueItem> {
        requireAdmin(actor)
        validatePage(page, size)
        val total = documentRepository.countReviewQueue(status)
        val content = documentRepository.findReviewQueue(status, size, page.toLong() * size).map { row ->
            AdminDriverDocumentQueueItem(row.document, row.driverName, row.driverPhone)
        }
        val totalPages = if (total == 0L) 0 else ((total + size - 1) / size).toInt()
        return PageResponse(content, page, size, total, totalPages, page + 1 < totalPages)
    }

    @Transactional
    fun reviewDocument(actor: AuthUser, documentId: Long, request: ReviewDriverDocumentRequest): DriverDocumentResponse {
        requireAdmin(actor)
        val initial = documentRepository.findById(documentId)
            ?: throw NotFoundException(ErrorCode.DRIVER_DOCUMENT_NOT_FOUND)
        val driver = lockDriver(initial.driverId)
        val document = documentRepository.findById(documentId)
            ?.takeIf(DriverDocumentResponse::isCurrent)
            ?: throw NotFoundException(ErrorCode.DRIVER_DOCUMENT_NOT_FOUND)
        val rejectionReason = request.rejectionReason?.trim()?.takeIf(String::isNotEmpty)
        if (request.status == DocumentVerificationStatus.REJECTED && rejectionReason == null) {
            throw BadRequestException(message = "Cần nhập rejection_reason khi từ chối giấy tờ")
        }
        val updated = documentRepository.review(
            id = documentId,
            status = request.status,
            rejectionReason = rejectionReason,
            adminId = actor.id,
            now = Instant.now(),
        )
        if (updated == 0) throw NotFoundException(ErrorCode.DRIVER_DOCUMENT_NOT_FOUND)

        if (document.documentType == licenseDocumentType) {
            driver.licenseVerified = request.status == DocumentVerificationStatus.APPROVED
            if (request.status == DocumentVerificationStatus.APPROVED) {
                driver.licenseNumber = document.documentNumber
                driver.licenseExpiryDate = document.expiresAt
                driver.licenseImageUrl = document.secureUrl
            }
        }
        val invalidatesApproval = request.status == DocumentVerificationStatus.REJECTED &&
            (document.documentType in requiredDocumentTypes || document.documentType == licenseDocumentType)
        if (invalidatesApproval) {
            driver.verificationStatus = "PENDING"
            driver.status = DriverStatus.OFFLINE
            driver.onlineVehicleType = null
        }
        if (document.documentType == licenseDocumentType || invalidatesApproval) driverRepository.save(driver)

        afterCommit {
            if (invalidatesApproval) removeFromAvailability(document.driverId)
            sendNotification(
                document.driverId,
                DriverAccountNotificationPayload(
                    type = PayloadType.DRIVER_DOCUMENT_REVIEWED,
                    status = request.status.name,
                    documentType = document.documentType,
                    rejectionReason = rejectionReason,
                ),
            )
        }
        return documentRepository.findById(documentId)
            ?: throw NotFoundException(ErrorCode.DRIVER_DOCUMENT_NOT_FOUND)
    }

    @Transactional
    fun verifySkill(actor: AuthUser, skillId: Long, request: VerifyDriverSkillRequest) {
        requireAdmin(actor)
        if (skillRepository.verify(skillId, request.verified) == 0) {
            throw NotFoundException(ErrorCode.DRIVER_SKILL_NOT_FOUND)
        }
    }

    @Transactional
    fun setVerificationStatus(
        actor: AuthUser,
        driverId: Long,
        request: SetDriverVerificationStatusRequest,
    ) {
        requireAdmin(actor)
        val driver = lockDriver(driverId)
        when (request.status) {
            DriverVerificationStatus.APPROVED -> {
                val approvedTypes = documentRepository.findCurrentForDriver(driverId)
                    .filter { it.verificationStatus == DocumentVerificationStatus.APPROVED }
                    .map { it.documentType }
                    .toSet()
                val missing = requiredDocumentTypes.filterNot(approvedTypes::contains)
                if (missing.isNotEmpty()) {
                    throw ConflictException(message = "Chưa duyệt đủ giấy tờ bắt buộc: ${missing.joinToString()}")
                }
                driver.verificationStatus = DriverVerificationStatus.APPROVED.name
                driver.licenseVerified = licenseDocumentType in approvedTypes
                driverRepository.save(driver)
                afterCommit {
                    sendNotification(
                        driverId,
                        DriverAccountNotificationPayload(
                            type = PayloadType.DRIVER_VERIFICATION_UPDATED,
                            status = DriverVerificationStatus.APPROVED.name,
                        ),
                    )
                }
            }

            DriverVerificationStatus.REJECTED -> {
                driver.verificationStatus = DriverVerificationStatus.REJECTED.name
                driver.status = DriverStatus.OFFLINE
                driver.onlineVehicleType = null
                driver.licenseVerified = false
                driverRepository.save(driver)
                val reason = request.rejectionReason?.trim()?.takeIf(String::isNotEmpty)
                afterCommit {
                    removeFromAvailability(driverId)
                    sendNotification(
                        driverId,
                        DriverAccountNotificationPayload(
                            type = PayloadType.DRIVER_VERIFICATION_UPDATED,
                            status = DriverVerificationStatus.REJECTED.name,
                            rejectionReason = reason,
                        ),
                    )
                }
            }
        }
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
