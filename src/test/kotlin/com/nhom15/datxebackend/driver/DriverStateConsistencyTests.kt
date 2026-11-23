package com.nhom15.datxebackend.driver

import com.nhom15.datxebackend.admin.repository.AdminDriverQueryRepository
import com.nhom15.datxebackend.admin.service.AdminDriverService
import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import com.nhom15.datxebackend.driver.verification.ApprovedCapability
import com.nhom15.datxebackend.driver.verification.CapabilityPreferenceRequest
import com.nhom15.datxebackend.driver.verification.DriverCapabilityService
import com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility
import com.nhom15.datxebackend.driver.verification.DriverVerificationProjection
import com.nhom15.datxebackend.driver.verification.DriverVerificationStorageService
import com.nhom15.datxebackend.driver.verification.DriverVerificationWorkflowService
import com.nhom15.datxebackend.driver.verification.LicenseEntitlement
import com.nhom15.datxebackend.driver.verification.LicenseValidity
import com.nhom15.datxebackend.driver.verification.VerificationProjectionRepository
import com.nhom15.datxebackend.driver.verification.VerificationStatus
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.tracking.service.LocationService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

class DriverStateConsistencyTests {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `F-004 forceOffline rejects BUSY driver without wiping onlineVehicleType and preserves BLOCKED status`() {
        val queryRepo = Mockito.mock(AdminDriverQueryRepository::class.java)
        val skillRepo = Mockito.mock(DriverSkillRepository::class.java)
        val driverRepo = Mockito.mock(DriverRepository::class.java)
        val locationService = Mockito.mock(LocationService::class.java)
        val notificationPort = Mockito.mock(NotificationPort::class.java)
        val admin = AuthUser(1L, "admin-uid", Role.ADMIN, "Admin", null, null, "ACTIVE")

        val busyDriver = Driver().apply {
            id = 10L
            firebaseUid = "drv-10"
            status = DriverStatus.BUSY
            onlineVehicleType = VehicleType.CAR
        }
        Mockito.`when`(driverRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(busyDriver))

        val service = AdminDriverService(queryRepo, skillRepo, driverRepo, locationService, notificationPort)
        assertThatThrownBy { service.forceOffline(admin, 10L) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(busyDriver.status).isEqualTo(DriverStatus.BUSY)
        assertThat(busyDriver.onlineVehicleType).isEqualTo(VehicleType.CAR)
        Mockito.verify(locationService, Mockito.never()).removeDriver(10L)

        // BLOCKED driver must NOT be downgraded to OFFLINE
        val blockedDriver = Driver().apply {
            id = 11L
            firebaseUid = "drv-11"
            status = DriverStatus.BLOCKED
            onlineVehicleType = VehicleType.CAR
        }
        Mockito.`when`(driverRepo.findByIdForUpdate(11L)).thenReturn(Optional.of(blockedDriver))
        service.forceOffline(admin, 11L)
        assertThat(blockedDriver.status).isEqualTo(DriverStatus.BLOCKED)
        assertThat(blockedDriver.onlineVehicleType).isNull()
        Mockito.verify(locationService).removeDriver(11L)
    }

    @Test
    fun `F-020 eKYC syncProjectionAndDriver preserves BUSY driver and BLOCKED driver without wiping onlineVehicleType`() {
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val storage = DriverVerificationStorageService(tempDir.toString(), "test-key")
        val projection = Mockito.mock(VerificationProjectionRepository::class.java)
        val driverRepo = Mockito.mock(DriverRepository::class.java)
        val locationService = Mockito.mock(LocationService::class.java)

        val busyDriver = Driver().apply {
            id = 20L
            firebaseUid = "drv-20"
            status = DriverStatus.BUSY
            onlineVehicleType = VehicleType.CAR
            verificationStatus = "APPROVED"
        }
        Mockito.`when`(driverRepo.findByFirebaseUid("drv-20")).thenReturn(Optional.of(busyDriver))
        Mockito.`when`(driverRepo.findByIdForUpdate(20L)).thenReturn(Optional.of(busyDriver))

        val workflow = DriverVerificationWorkflowService(
            jdbc = jdbc,
            mapper = mapper,
            storage = storage,
            projection = projection,
            driverRepository = driverRepo,
            locationService = locationService,
        )

        val syncMethod = DriverVerificationWorkflowService::class.java.declaredMethods
            .first { it.name == "syncProjectionAndDriver" }
        syncMethod.isAccessible = true
        syncMethod.invoke(
            workflow,
            "drv-20",
            UUID.randomUUID(),
            2L,
            VerificationStatus.REVOKED,
            emptyMap<String, Any?>(),
            null,
            "revoked",
            null,
            false,
        )

        assertThat(busyDriver.verificationStatus).isEqualTo("REJECTED")
        assertThat(busyDriver.status).isEqualTo(DriverStatus.BUSY)
        assertThat(busyDriver.onlineVehicleType).isEqualTo(VehicleType.CAR)
        Mockito.verify(locationService, Mockito.never()).removeDriver(20L)
    }

    @Test
    fun `F-044 DriverCapabilityService blocks disabling while BUSY and removes Redis GEO when ONLINE flips OFFLINE`() {
        val driverRepo = Mockito.mock(DriverRepository::class.java)
        val projectionRepo = Mockito.mock(VerificationProjectionRepository::class.java)
        val eligibility = Mockito.mock(DriverVerificationEligibility::class.java)
        val locationService = Mockito.mock(LocationService::class.java)
        val userCache = Mockito.mock(AuthenticatedUserCache::class.java)

        val appId = UUID.randomUUID()
        val ent = LicenseEntitlement("B", LicenseValidity.NO_EXPIRY, null)
        val cap = ApprovedCapability(
            groupId = "car-auto",
            applicationId = appId,
            submissionRevision = 1L,
            approvalRevision = 1L,
            policyVersion = com.nhom15.datxebackend.driver.verification.LicenseEligibilityPolicy.VERSION,
            entitlements = listOf(ent),
        )
        val record = DriverVerificationProjection(
            status = VerificationStatus.APPROVED,
            entitlements = listOf(ent),
            revision = 1L,
            cccdExpiresOn = LocalDate.now().plusYears(5),
            capabilities = listOf(cap),
            enabledGroups = setOf("car-auto"),
            preferenceVersion = 1L,
        )
        Mockito.`when`(projectionRepo.current("drv-30")).thenReturn(record)
        Mockito.`when`(eligibility.currentAtSource()).thenReturn(true)

        val busyDriver = Driver().apply {
            id = 30L
            firebaseUid = "drv-30"
            status = DriverStatus.BUSY
            onlineVehicleType = VehicleType.CAR
        }
        Mockito.`when`(driverRepo.findByIdForUpdate(30L)).thenReturn(Optional.of(busyDriver))

        val service = DriverCapabilityService(driverRepo, projectionRepo, eligibility, locationService, userCache)
        val actor = AuthUser(30L, "drv-30", Role.DRIVER, "Driver 30", null, null, "BUSY")

        assertThatThrownBy {
            service.update(actor, "car-auto", CapabilityPreferenceRequest(1L, 1L, enabled = false))
        }.isInstanceOf(ConflictException::class.java)

        // ONLINE driver disabling last capability for onlineVehicleType flips OFFLINE, evicts cache, and removes Redis GEO
        val onlineDriver = Driver().apply {
            id = 30L
            firebaseUid = "drv-30"
            status = DriverStatus.ONLINE
            onlineVehicleType = VehicleType.CAR
        }
        Mockito.`when`(driverRepo.findByIdForUpdate(30L)).thenReturn(Optional.of(onlineDriver))
        service.update(actor, "car-auto", CapabilityPreferenceRequest(1L, 1L, enabled = false))
        assertThat(onlineDriver.status).isEqualTo(DriverStatus.OFFLINE)
        assertThat(onlineDriver.onlineVehicleType).isNull()
        Mockito.verify(userCache).evictAfterCommit("drv-30")
        Mockito.verify(locationService).removeDriver(30L)
    }

    @Test
    fun `F-027 statusAfterRide never restores ineligible, flagged, deleted, BLOCKED, or null-onlineVehicleType driver to ONLINE`() {
        val projectionRepo = Mockito.mock(VerificationProjectionRepository::class.java)
        val eligibility = DriverVerificationEligibility(projectionRepo)

        val approvedProjection = DriverVerificationProjection(
            status = VerificationStatus.APPROVED,
            entitlements = emptyList(),
            revision = 1L,
            cccdExpiresOn = LocalDate.now().minusDays(1), // EXPIRED CCCD!
            capabilities = emptyList(),
            enabledGroups = emptySet(),
            preferenceVersion = 1L,
        )
        Mockito.`when`(projectionRepo.current("drv-40")).thenReturn(approvedProjection)

        val expiredDriver = Driver().apply {
            id = 40L
            firebaseUid = "drv-40"
            status = DriverStatus.BUSY
            onlineVehicleType = VehicleType.CAR
            verificationStatus = "APPROVED"
        }
        assertThat(eligibility.statusAfterRide(expiredDriver)).isEqualTo(DriverStatus.OFFLINE)
        assertThat(expiredDriver.onlineVehicleType).isNull()

        val nullTypeDriver = Driver().apply {
            id = 41L
            firebaseUid = "drv-40"
            status = DriverStatus.BUSY
            onlineVehicleType = null
            verificationStatus = "APPROVED"
        }
        assertThat(eligibility.statusAfterRide(nullTypeDriver)).isEqualTo(DriverStatus.OFFLINE)

        val blockedDriver = Driver().apply {
            id = 42L
            firebaseUid = "drv-40"
            status = DriverStatus.BLOCKED
            onlineVehicleType = VehicleType.CAR
            verificationStatus = "APPROVED"
        }
        assertThat(eligibility.statusAfterRide(blockedDriver)).isEqualTo(DriverStatus.BLOCKED)
    }
}
