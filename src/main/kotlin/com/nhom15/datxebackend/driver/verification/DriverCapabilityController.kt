package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.tracking.service.LocationService
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

data class CapabilityPreferenceRequest(
    @field:Positive val approvalRevision: Long,
    @field:PositiveOrZero val preferenceVersion: Long,
    val enabled: Boolean,
)
data class CapabilityDisplay(
    val groupId: String,
    val label: String,
    val approved: Boolean,
    val enabled: Boolean,
    val effective: Boolean,
    val reasonCode: String?,
    val expiresOn: LocalDate?,
    val grant: ApprovedCapability?,
)
data class DriverCapabilitiesView(
    val enabled: Boolean,
    val approvalRevision: Long?,
    val preferenceVersion: Long,
    val acceptingVehicleTypes: List<VehicleType>,
    val groups: List<CapabilityDisplay>,
)

@Service
class DriverCapabilityService @Autowired constructor(
    private val drivers: DriverRepository,
    private val projection: VerificationProjectionRepository?,
    private val verification: DriverVerificationEligibility,
    private val locationService: LocationService? = null,
    private val userCache: AuthenticatedUserCache? = null,
) {
    fun forDriver(actor: AuthUser): DriverCapabilitiesView {
        val driver = drivers.findById(actor.id).orElseThrow { ForbiddenException() }
        return view(projection?.current(driver.firebaseUid), driver.canUseCapabilities())
    }

    fun forAdmin(applicationId: UUID): DriverCapabilitiesView =
        view(projection?.forApplication(applicationId), true)

    @Transactional
    fun update(actor: AuthUser, groupId: String, input: CapabilityPreferenceRequest): DriverCapabilitiesView {
        val repository = projection ?: throw ConflictException(message = "Dịch vụ lái hộ chưa được bật")
        val driver = drivers.findByIdForUpdate(actor.id).orElseThrow { ForbiddenException() }
        val record = repository.current(driver.firebaseUid)
            ?: throw ConflictException(message = "Chưa đồng bộ hồ sơ, hãy làm mới")
        if (record.revision != input.approvalRevision || record.preferenceVersion != input.preferenceVersion ||
            !verification.currentAtSource()
        ) throw ConflictException(message = "Dữ liệu nhận chuyến đã thay đổi, hãy làm mới")
        val state = view(record, driver.canUseCapabilities())
        val group = state.groups.firstOrNull { it.groupId == groupId } ?: throw ForbiddenException()
        if (input.enabled && (!group.approved || group.reasonCode != null)) {
            throw ForbiddenException(message = "Nhóm xe chưa đủ điều kiện để bật")
        }
        if (!input.enabled && driver.status == DriverStatus.BUSY) {
            throw ConflictException(message = "Không thể tắt nhóm xe khi tài xế đang thực hiện chuyến đi")
        }
        val next = if (input.enabled) record.enabledGroups + groupId else record.enabledGroups - groupId
        if (next == record.enabledGroups) return state
        val updated = record.copy(enabledGroups = next, preferenceVersion = record.preferenceVersion + 1)
        repository.savePreferences(actor.id, record.revision, updated.preferenceVersion, next)
        val result = view(updated, driver.canUseCapabilities())
        if (driver.status == DriverStatus.ONLINE && driver.onlineVehicleType !in result.acceptingVehicleTypes) {
            driver.status = DriverStatus.OFFLINE
            driver.onlineVehicleType = null
            drivers.save(driver)
            userCache?.evictAfterCommit(driver.firebaseUid)
            afterCommit { runCatching { locationService?.removeDriver(driver.id) } }
        }
        return result
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

    private fun Driver.canUseCapabilities() =
        isFlagged != true && deletedAt == null && verificationStatus != "SUSPENDED" && accountStatus == "ACTIVE" && status != DriverStatus.BLOCKED

    private fun view(record: DriverVerificationProjection?, usable: Boolean): DriverCapabilitiesView {
        val today = capabilityToday()
        val current = record?.status == VerificationStatus.APPROVED
        val effective = if (usable) record?.effectiveGroups(today).orEmpty() else emptySet()
        val groups = supportedCapabilityGroups.map { group ->
            val grant = record?.capabilities?.firstOrNull { it.groupId == group.id }
            val reason = when {
                projection == null -> "WORKFLOW_DISABLED"
                !usable -> "DRIVER_UNAVAILABLE"
                record == null -> "VERIFICATION_NOT_SYNCHRONIZED"
                !current -> "VERIFICATION_NOT_APPROVED"
                record.cccdExpiresOn?.isBefore(today) == true -> "CCCD_EXPIRED"
                grant == null -> "CAPABILITY_NOT_APPROVED"
                !grant.valid(today) -> "CAPABILITY_EXPIRED_OR_POLICY_CHANGED"
                else -> null
            }
            val expiry = grant?.entitlements?.takeUnless { entries -> entries.any { it.kind == LicenseValidity.NO_EXPIRY } }
                ?.mapNotNull { it.expiresOn }?.maxOrNull()
            val expiresOn = grant?.let { listOfNotNull(expiry, record.cccdExpiresOn).minOrNull() }
            CapabilityDisplay(group.id, group.label, grant != null, group.id in record?.enabledGroups.orEmpty(),
                group.id in effective, reason, expiresOn, grant)
        }
        return DriverCapabilitiesView(projection != null, record?.revision?.takeIf { current },
            record?.preferenceVersion ?: 0, VehicleType.entries.filter { coversOnline(effective, it) }, groups)
    }
}

@RestController
class DriverCapabilityController(private val service: DriverCapabilityService) {
    @GetMapping("/api/driver/capabilities")
    @PreAuthorize("hasRole('DRIVER')")
    fun own(@CurrentUser actor: AuthUser) = ApiResponse.ok(service.forDriver(actor))

    @PutMapping("/api/driver/capabilities/{groupId}/preference")
    @PreAuthorize("hasRole('DRIVER')")
    fun preference(@CurrentUser actor: AuthUser, @PathVariable groupId: String,
                   @Valid @RequestBody input: CapabilityPreferenceRequest) =
        ApiResponse.ok(service.update(actor, groupId, input))

    @GetMapping("/api/admin/vehicle-catalog/applications/{applicationId}/capabilities")
    @PreAuthorize("hasRole('ADMIN')")
    fun admin(@PathVariable applicationId: UUID) = ApiResponse.ok(service.forAdmin(applicationId))
}

