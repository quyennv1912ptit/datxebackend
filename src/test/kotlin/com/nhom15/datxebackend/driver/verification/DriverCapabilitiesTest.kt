package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriverCapabilitiesTest {
    private val today = capabilityToday()
    private val issue = LocalDate.of(2025, 1, 1)
    private val actor = AuthUser(1, "synthetic-capability-driver", Role.DRIVER, null, null, null, "OFFLINE")
    private val application = UUID.randomUUID()
    private val b = LicenseEntitlement("B", LicenseValidity.HAS_EXPIRY, today.plusDays(20))
    private val event = VerificationEvent(1, application, 8, actor.firebaseUid, VerificationStatus.APPROVED,
        listOf(b, LicenseEntitlement("A1", LicenseValidity.NO_EXPIRY, null)),
        capabilityReview = CapabilityReview(LicenseEligibilityPolicy.VERSION, 6, issue, LicenseRestriction.VERIFIED_NONE,
            selectedGroupIds = listOf("car-auto", "moto-50-125", "moto-175-plus", "bus")))
    private val record = DriverVerificationProjection(event.status, event.entitlements, revision = event.revision,
        capabilities = grants(event, today))

    @Test
    fun `multiple classes grant only selected eligible groups and legacy approvals grant none`() {
        assertEquals(setOf("car-auto", "moto-50-125"), record.capabilities.map { it.groupId }.toSet())
        assertEquals(listOf("B"), record.capabilities.single { it.groupId == "car-auto" }.entitlements.map { it.licenseClass })
        assertEquals(application, record.capabilities.first().applicationId)
        assertEquals(6L, record.capabilities.first().submissionRevision)
        assertTrue(grants(event.copy(capabilityReview = null), today).isEmpty())
        assertTrue(grants(event.copy(capabilityReview = event.capabilityReview!!.copy(policyVersion = "old")), today).isEmpty())
        assertTrue(grants(event.copy(status = VerificationStatus.REVOKED), today).isEmpty())
    }

    @Test
    fun `preferences default off and effective access ends at expiry newer revision and revocation`() {
        assertFalse(coversOnline(record.effectiveGroups(today), VehicleType.CAR))
        val enabled = record.copy(enabledGroups = setOf("car-auto", "moto-50-125"))
        assertTrue(coversOnline(enabled.effectiveGroups(today), VehicleType.CAR))
        assertTrue(coversTrip(enabled.effectiveGroups(today), VehicleType.CAR, Transmission.AUTO))
        assertFalse(coversTrip(enabled.effectiveGroups(today), VehicleType.CAR, Transmission.MANUAL))
        assertFalse(coversTrip(enabled.effectiveGroups(today), VehicleType.CAR, null))
        assertTrue(coversOnline(enabled.effectiveGroups(today), VehicleType.MOTORCYCLE))
        assertFalse(coversTrip(enabled.effectiveGroups(today), VehicleType.MOTORCYCLE, null))
        assertFalse(coversOnline(enabled.effectiveGroups(today.plusDays(21)), VehicleType.CAR))
        assertTrue(enabled.copy(revision = 9).effectiveGroups(today).isEmpty())
        assertTrue(enabled.copy(status = VerificationStatus.REVOKED).effectiveGroups(today).isEmpty())
    }

    @Test
    fun `driver can toggle only own approved groups and stale preference versions are rejected`() {
        val drivers = mock(DriverRepository::class.java)
        val projection = mock(VerificationProjectionRepository::class.java)
        val eligibility = mock(DriverVerificationEligibility::class.java)
        val driver = Driver().apply { id = actor.id; firebaseUid = actor.firebaseUid }
        val service = DriverCapabilityService(drivers, projection, eligibility)
        `when`(drivers.findByIdForUpdate(actor.id)).thenReturn(Optional.of(driver))
        `when`(projection.current(actor.firebaseUid)).thenReturn(record)
        `when`(eligibility.currentAtSource()).thenReturn(true)
        assertFailsWith<ForbiddenException> { service.update(actor, "car-manual", CapabilityPreferenceRequest(8, 0, true)) }
        assertFailsWith<ConflictException> { service.update(actor, "car-auto", CapabilityPreferenceRequest(7, 0, true)) }
        val enabled = service.update(actor, "car-auto", CapabilityPreferenceRequest(8, 0, true))
        assertEquals(listOf(VehicleType.CAR), enabled.acceptingVehicleTypes)
        verify(projection).savePreferences(actor.id, 8, 1, setOf("car-auto"))
        `when`(projection.current(actor.firebaseUid)).thenReturn(record.copy(
            preferenceVersion = 1, enabledGroups = setOf("car-auto")))
        assertFailsWith<ConflictException> { service.update(actor, "car-auto", CapabilityPreferenceRequest(8, 0, false)) }
        assertTrue(service.update(actor, "car-auto", CapabilityPreferenceRequest(8, 1, false)).acceptingVehicleTypes.isEmpty())
        verify(projection).savePreferences(actor.id, 8, 2, emptySet())
    }
}

