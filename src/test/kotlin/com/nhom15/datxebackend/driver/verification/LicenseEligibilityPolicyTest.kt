package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.vehicle.catalog.VehicleCatalog
import com.nhom15.datxebackend.vehicle.catalog.VehicleCatalogController
import com.nhom15.datxebackend.vehicle.catalog.VehicleGroup
import com.nhom15.datxebackend.vehicle.catalog.LicensePreviewRequest
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseEligibilityPolicyTest {
    private val policy = LicenseEligibilityPolicy()
    private val today = LocalDate.of(2026, 11, 16)
    private val before = LocalDate.of(2024, 12, 31)
    private val after = LocalDate.of(2025, 1, 1)
    private val until = LocalDate.of(2030, 1, 1)

    private fun group(id: String) = VehicleCatalog.groups.single { it.id == id }
    private fun license(grade: String, issue: LocalDate = after, expiry: LocalDate? = null) =
        LicenseEvidence(listOf(grade), issue, expiry, expiry == null, LicenseRestriction.VERIFIED_NONE)
    private fun result(license: LicenseEvidence, vehicle: VehicleGroup, use: LicensePurpose = LicensePurpose.PAID_DRIVER) =
        policy.evaluate(license, vehicle, use, today)

    @Test
    fun `displacement and power limits distinguish retained and new licenses`() {
        val old = license("A1", before)
        val new = license("A1")
        val electric = group("moto-electric-4-11")
        val petrol = group("moto-50-125")
        assertEquals(Eligibility.ELIGIBLE, result(new, petrol).status)
        assertEquals(Eligibility.INELIGIBLE, result(new, petrol.copy(displacementCc = 126)).status)
        assertEquals(Eligibility.ELIGIBLE, result(old, petrol.copy(displacementCc = 174)).status)
        assertEquals(Eligibility.INELIGIBLE, result(old, petrol.copy(displacementCc = 175)).status)
        assertEquals(Eligibility.ELIGIBLE, result(new, electric).status)
        assertEquals(Eligibility.INELIGIBLE, result(new, electric.copy(electricMotorKw = 11.001)).status)
        assertEquals(Eligibility.ELIGIBLE, result(old, electric.copy(electricMotorKw = 13.999)).status)
        assertEquals(Eligibility.INELIGIBLE, result(old, electric.copy(electricMotorKw = 14.0)).status)
        assertEquals(Eligibility.ELIGIBLE, result(license("A2", before), electric.copy(electricMotorKw = 14.0)).status)
        assertEquals(Eligibility.ELIGIBLE, result(license("B1"), petrol).status)
        assertEquals(Eligibility.INELIGIBLE, result(license("B1"), group("car-auto")).status)
        assertEquals(Eligibility.REQUIRES_MANUAL_REVIEW, result(new.copy(issuedOn = null), petrol).status)
    }

    @Test
    fun `seats gearbox and paid driving are evaluated independently`() {
        val retained = license("B1", before, until)
        val automatic = group("car-auto")
        val manual = group("car-manual")
        assertEquals(listOf("LEGACY_B1_NON_PROFESSIONAL"), result(retained, automatic).reasonCodes)
        assertEquals(Eligibility.ELIGIBLE, result(retained, automatic, LicensePurpose.PRIVATE).status)
        val b = license("B", expiry = until)
        assertEquals(Eligibility.ELIGIBLE, result(b, manual.copy(passengerSeats = 8)).status)
        assertEquals(listOf("PASSENGER_SEAT_LIMIT"), result(b, manual.copy(passengerSeats = 9)).reasonCodes)
        val restricted = b.copy(restrictionCode = "B.01")
        assertEquals(Eligibility.ELIGIBLE, result(restricted, automatic).status)
        assertEquals(listOf("AUTOMATIC_ONLY"), result(restricted, manual).reasonCodes)
        assertEquals(Eligibility.REQUIRES_MANUAL_REVIEW, result(b.copy(restrictionCode = "B.02"), manual).status)
        val converted = license("A").copy(restrictionCode = "A.03")
        assertEquals(Eligibility.ELIGIBLE, result(converted, group("moto-126-174")).status)
        assertEquals(Eligibility.INELIGIBLE, result(converted, group("moto-175-plus")).status)
    }

    @Test
    fun `truck limits use designed mass and seed never activates trucks buses or trailers`() {
        val truck = group("truck")
        val b = license("B", expiry = until)
        assertEquals(listOf("VEHICLE_OUTSIDE_MVP"), result(b, truck).reasonCodes)
        val legalFixture = truck.copy(active = true, supported = true)
        assertEquals(Eligibility.ELIGIBLE, result(b, legalFixture.copy(designedMassKg = 3500)).status)
        assertEquals(Eligibility.INELIGIBLE, result(b, legalFixture.copy(designedMassKg = 3501)).status)
        val c1 = license("C1", expiry = until)
        assertEquals(Eligibility.ELIGIBLE, result(c1, legalFixture.copy(designedMassKg = 7500)).status)
        assertEquals(Eligibility.INELIGIBLE, result(c1, legalFixture.copy(designedMassKg = 7501)).status)
        assertEquals(Eligibility.REQUIRES_MANUAL_REVIEW,
            result(c1, legalFixture.copy(designedMassKg = 4200, trailerMassKg = 751)).status)
        listOf("truck", "bus", "car-trailer").forEach { assertFalse(group(it).supported) }
        assertEquals(2, VehicleCatalog.bookingTypes.size)
    }

    @Test
    fun `expiration uses the displayed date and unknown evidence never expands eligibility`() {
        val automatic = group("car-auto")
        val b = license("B", expiry = today)
        assertEquals(Eligibility.ELIGIBLE, result(b, automatic).status)
        assertEquals(listOf("LICENSE_EXPIRED"), result(b.copy(expiresOn = today.minusDays(1)), automatic).reasonCodes)
        assertEquals(Eligibility.REQUIRES_MANUAL_REVIEW,
            result(b.copy(restriction = LicenseRestriction.UNKNOWN), automatic).status)
        assertEquals(Eligibility.REQUIRES_MANUAL_REVIEW, result(license("B"), automatic).status)
    }

    @Test
    fun `catalog exposes stable booking keys and unsupported groups over HTTP`() {
        val mvc = MockMvcBuilders.standaloneSetup(VehicleCatalogController(policy)).build()
        val truckIndex = VehicleCatalog.groups.indexOfFirst { it.id == "truck" }
        mvc.perform(get("/api/vehicle-catalog"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.bookingTypes.CAR").exists())
            .andExpect(jsonPath("$.data.bookingTypes.MOTORCYCLE").exists())
            .andExpect(jsonPath("$.data.groups[$truckIndex].id").value("truck"))
            .andExpect(jsonPath("$.data.groups[$truckIndex].supported").value(false))
    }

    @Test
    fun `admin preview emits a reason and version for every supported group without activating anything`() {
        val result = VehicleCatalogController(policy).preview(LicensePreviewRequest(listOf("B"))).data!!
        assertEquals(VehicleCatalog.groups.count { it.active && it.supported }, result.size)
        assertTrue(result.all { it.result.policyVersion == LicenseEligibilityPolicy.VERSION })
        assertTrue(result.all { it.result.reasonCodes == listOf("ISSUE_DATE_MISSING") })
    }
}

