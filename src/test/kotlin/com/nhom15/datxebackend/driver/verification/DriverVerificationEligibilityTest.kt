package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.driver.dto.DriverSkillResponse
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriverVerificationEligibilityTest {
    private val projection = mock(VerificationProjectionRepository::class.java)
    private val sync = mock(VerificationSync::class.java)
    private val skills = mock(DriverSkillRepository::class.java)
    private val service = DriverVerificationEligibility(projection, sync)
    private val driver = Driver().apply { id = 7; firebaseUid = "synthetic-driver"; verificationStatus = "APPROVED" }
    private val head = VerificationHead(UUID.randomUUID(), 7)

    private fun motorcycleRecord(): DriverVerificationProjection {
        val entitlement = LicenseEntitlement("A", LicenseValidity.NO_EXPIRY, null)
        val groups = supportedCapabilityGroups.filter { it.vehicleType == VehicleType.MOTORCYCLE }
        return DriverVerificationProjection(VerificationStatus.APPROVED, listOf(entitlement),
            revision = 4, capabilities = groups.map {
                ApprovedCapability(it.id, UUID.randomUUID(), 3, 4, LicenseEligibilityPolicy.VERSION, listOf(entitlement))
            }, enabledGroups = groups.mapTo(mutableSetOf()) { it.id })
    }

    @Test
    fun `explicit no expiry authorizes only the relevant approved class and a current sync`() {
        `when`(projection.current(driver.firebaseUid)).thenReturn(
            motorcycleRecord(),
        )
        `when`(projection.synchronizedHead()).thenReturn(head)
        `when`(sync.confirms(head)).thenReturn(true)
        `when`(skills.findAllForDriver(driver.id)).thenReturn(listOf(
            DriverSkillResponse(1, VehicleType.MOTORCYCLE, Transmission.AUTO, "A1", true),
            DriverSkillResponse(2, VehicleType.CAR, Transmission.MANUAL, "B", true),
        ))
        assertTrue(service.mayDrive(driver, VehicleType.MOTORCYCLE, Transmission.AUTO, skills))
        assertTrue(service.mayDrive(driver, VehicleType.MOTORCYCLE, Transmission.MANUAL, skills))
        assertFalse(service.mayDrive(driver, VehicleType.CAR, Transmission.MANUAL, skills))
        `when`(sync.confirms(head)).thenReturn(false)
        assertFalse(service.mayDrive(driver, VehicleType.MOTORCYCLE, Transmission.AUTO, skills))
    }

    @Test
    fun `an approved driver loses eligibility after the confirmed CCCD expiry`() {
        val today = capabilityToday()
        val record = motorcycleRecord()
        `when`(skills.findAllForDriver(driver.id)).thenReturn(listOf(
            DriverSkillResponse(1, VehicleType.MOTORCYCLE, Transmission.AUTO, "A1", true),
        ))
        `when`(projection.current(driver.firebaseUid)).thenReturn(record.copy(cccdExpiresOn = today))
        assertTrue(service.mayDrive(driver, VehicleType.MOTORCYCLE, Transmission.AUTO, skills, verifySource = false))
        `when`(projection.current(driver.firebaseUid)).thenReturn(record.copy(cccdExpiresOn = today.minusDays(1)))
        assertFalse(service.mayDrive(driver, VehicleType.MOTORCYCLE, Transmission.AUTO, skills, verifySource = false))
    }

    @Test
    fun `unknown expiry and revoked pending or missing projections never inherit legacy approval`() {
        `when`(projection.synchronizedHead()).thenReturn(head)
        `when`(sync.confirms(head)).thenReturn(true)
        `when`(skills.findAllForDriver(driver.id)).thenReturn(listOf(
            DriverSkillResponse(1, VehicleType.MOTORCYCLE, Transmission.AUTO, "A1", true),
        ))
        assertFalse(service.mayDrive(driver, VehicleType.MOTORCYCLE, Transmission.AUTO, skills))
        for (status in listOf(VerificationStatus.APPROVED, VerificationStatus.REVOKED,
            VerificationStatus.REJECTED, VerificationStatus.NEEDS_MORE_INFO)) {
            `when`(projection.current(driver.firebaseUid)).thenReturn(
                DriverVerificationProjection(status, listOf(LicenseEntitlement("A1", LicenseValidity.UNKNOWN, null))),
            )
            assertFalse(service.mayDrive(driver, VehicleType.MOTORCYCLE, Transmission.AUTO, skills))
        }
    }

    @Test
    fun `unlimited expiry license allows null licenseExpiryDate and native postgres mode needs no polling sync`() {
        val legacyOnly = DriverVerificationEligibility()
        val unlimitedDriver = Driver().apply {
            id = 9
            firebaseUid = "unlimited-driver"
            verificationStatus = "APPROVED"
            licenseClass = "A1"
            licenseExpiryDate = null
        }
        assertTrue(legacyOnly.mayDrive(unlimitedDriver, VehicleType.MOTORCYCLE, Transmission.AUTO, skills))

        val expiredDriver = Driver().apply {
            id = 10
            firebaseUid = "expired-driver"
            verificationStatus = "APPROVED"
            licenseClass = "A1"
            licenseExpiryDate = capabilityToday().minusDays(1)
        }
        assertFalse(legacyOnly.mayDrive(expiredDriver, VehicleType.MOTORCYCLE, Transmission.AUTO, skills))

        val nativeSingleSource = DriverVerificationEligibility(projection, null)
        assertTrue(nativeSingleSource.currentAtSource())
        `when`(projection.current(driver.firebaseUid)).thenReturn(motorcycleRecord())
        `when`(skills.findAllForDriver(driver.id)).thenReturn(listOf(
            DriverSkillResponse(1, VehicleType.MOTORCYCLE, Transmission.AUTO, "A1", true),
        ))
        assertTrue(nativeSingleSource.mayGoOnline(driver, VehicleType.MOTORCYCLE, skills))
    }
}
