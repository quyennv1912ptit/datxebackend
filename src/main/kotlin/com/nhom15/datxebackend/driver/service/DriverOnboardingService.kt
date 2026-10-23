package com.nhom15.datxebackend.driver.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.driver.dto.DriverDocumentResponse
import com.nhom15.datxebackend.driver.dto.DriverSkillInput
import com.nhom15.datxebackend.driver.dto.DriverSkillResponse
import com.nhom15.datxebackend.driver.dto.DriverVerificationSummary
import com.nhom15.datxebackend.driver.dto.SubmitDriverDocumentRequest
import com.nhom15.datxebackend.driver.dto.UpdateDriverSkillsRequest
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverDocumentRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import com.nhom15.datxebackend.uploads.CloudinaryFolders
import com.nhom15.datxebackend.tracking.service.LocationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class DriverOnboardingService(
    private val driverRepository: DriverRepository,
    private val documentRepository: DriverDocumentRepository,
    private val skillRepository: DriverSkillRepository,
    private val cloudinaryAssetService: CloudinaryAssetService,
    private val locationService: LocationService,
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

    @Transactional
    fun submitDocument(actor: AuthUser, request: SubmitDriverDocumentRequest): DriverDocumentResponse {
        requireDriver(actor)
        val driver = lockDriver(actor.id)
        val documentType = request.documentType.trim().uppercase()
        val expectedFolder = "${CloudinaryFolders.driverDocuments(actor.id)}/"
        val publicId = cloudinaryAssetService.requireOwnedAsset(request.publicId, expectedFolder)
        cloudinaryAssetService.validateCloudinaryImage(request.secureUrl, publicId)

        val invalidatesApproval = documentType in requiredDocumentTypes || documentType == licenseDocumentType
        if (invalidatesApproval && driver.status == DriverStatus.BUSY) {
            throw ConflictException(message = "Không thể thay giấy tờ bắt buộc khi đang thực hiện chuyến")
        }

        documentRepository.markNotCurrent(actor.id, documentType)
        val document = documentRepository.insert(
            driverId = actor.id,
            documentType = documentType,
            documentNumber = request.documentNumber?.trim()?.takeIf(String::isNotEmpty),
            secureUrl = request.secureUrl.trim(),
            publicId = publicId,
            expiresAt = request.expiresAt,
        )

        if (documentType == licenseDocumentType) {
            driver.licenseNumber = request.documentNumber?.trim()?.takeIf(String::isNotEmpty)
            driver.licenseExpiryDate = request.expiresAt
            driver.licenseImageUrl = request.secureUrl.trim()
            driver.licenseVerified = false
        }
        val needsReverification = invalidatesApproval || driver.verificationStatus == "REJECTED"
        if (needsReverification) {
            driver.verificationStatus = "PENDING"
            driver.status = DriverStatus.OFFLINE
            driver.onlineVehicleType = null
        }
        driverRepository.save(driver)
        if (needsReverification) afterCommit {
            try {
                locationService.removeDriver(actor.id)
            } catch (e: Exception) {
                log.error("Không xóa được tài xế {} khỏi Redis sau khi nộp lại giấy tờ", actor.id, e)
            }
        }
        return document
    }

    @Transactional(readOnly = true)
    fun documents(actor: AuthUser): List<DriverDocumentResponse> {
        requireDriver(actor)
        requireDriverExists(actor.id)
        return documentRepository.findCurrentForDriver(actor.id)
    }

    @Transactional(readOnly = true)
    fun verification(actor: AuthUser): DriverVerificationSummary {
        requireDriver(actor)
        val driver = findDriver(actor.id)
        val currentDocuments = documentRepository.findCurrentForDriver(actor.id)
        val approvedTypes = currentDocuments
            .filter { it.verificationStatus.name == "APPROVED" }
            .map { it.documentType }
            .toSet()
        val skills = skillRepository.findAllForDriver(actor.id)
        return DriverVerificationSummary(
            verificationStatus = driver.verificationStatus ?: "PENDING",
            requiredDocumentTypes = requiredDocumentTypes,
            missingDocuments = requiredDocumentTypes.filterNot(approvedTypes::contains),
            unverifiedSkills = skills.filterNot { it.verified },
        )
    }

    @Transactional(readOnly = true)
    fun skills(actor: AuthUser): List<DriverSkillResponse> {
        requireDriver(actor)
        requireDriverExists(actor.id)
        return skillRepository.findAllForDriver(actor.id)
    }

    @Transactional
    fun updateSkills(actor: AuthUser, request: UpdateDriverSkillsRequest): List<DriverSkillResponse> {
        requireDriver(actor)
        val driver = lockDriver(actor.id)
        val desired = request.skills.map(::normalizedSkill)
        if (desired.distinctBy(SkillKey::of).size != desired.size) {
            throw BadRequestException(message = "Danh sách kỹ năng có mục bị lặp")
        }

        val existing = skillRepository.findAllForDriverForUpdate(actor.id)
        val desiredKeys = desired.map(SkillKey::of).toSet()
        val existingWithKeys = existing.map { it to SkillKey.of(it) }
        val removed = existingWithKeys.filterNot { (_, key) -> key in desiredKeys }
        if (driver.status != DriverStatus.OFFLINE && driver.onlineVehicleType != null &&
            removed.any { (skill, _) -> skill.vehicleType == driver.onlineVehicleType }
        ) {
            throw ConflictException(message = "Không thể xóa kỹ năng của loại xe đang được dùng khi online")
        }

        removed.forEach { (skill, _) -> skillRepository.delete(skill.id, actor.id) }
        val existingKeys = existingWithKeys.map { it.second }.toSet()
        desired.filterNot { SkillKey.of(it) in existingKeys }.forEach { skill ->
            skillRepository.insert(actor.id, skill.vehicleType, skill.transmission, skill.licenseClass)
        }
        return skillRepository.findAllForDriver(actor.id)
    }

    private fun normalizedSkill(input: DriverSkillInput) = input.copy(
        licenseClass = input.licenseClass?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
    )

    private fun findDriver(driverId: Long): Driver = driverRepository.findById(driverId)
        .orElseThrow { NotFoundException(ErrorCode.USER_NOT_FOUND, "Không tìm thấy tài xế") }

    private fun lockDriver(driverId: Long): Driver = driverRepository.findByIdForUpdate(driverId)
        .orElseThrow { NotFoundException(ErrorCode.USER_NOT_FOUND, "Không tìm thấy tài xế") }

    private fun requireDriverExists(driverId: Long) {
        if (!driverRepository.existsById(driverId)) {
            throw NotFoundException(ErrorCode.USER_NOT_FOUND, "Không tìm thấy tài xế")
        }
    }

    private fun requireDriver(actor: AuthUser) {
        if (actor.role != Role.DRIVER) throw ForbiddenException(message = "Chỉ tài xế được quản lý hồ sơ tài xế")
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

    private data class SkillKey(
        val vehicleType: String,
        val transmission: String,
        val licenseClass: String?,
    ) {
        companion object {
            fun of(skill: DriverSkillInput) = SkillKey(
                skill.vehicleType.name,
                skill.transmission.name,
                skill.licenseClass?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
            )

            fun of(skill: DriverSkillResponse) = SkillKey(
                skill.vehicleType.name,
                skill.transmission.name,
                skill.licenseClass?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
            )
        }
    }
}
