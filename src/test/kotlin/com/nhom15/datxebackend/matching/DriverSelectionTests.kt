package com.nhom15.datxebackend.matching

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.port.NearbyDriver
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.verification.*
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.matching.service.DriverSelectionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.LocalDate

class DriverSelectionTests {
    private val driverRepository = Mockito.mock(DriverRepository::class.java)
    private val projection = Mockito.mock(VerificationProjectionRepository::class.java)
    private val service = DriverSelectionService(driverRepository, MatchingProperties(),
        verification = DriverVerificationEligibility(projection))

    private val nearby = listOf(
        NearbyDriver(driverId = 2, distanceKm = 0.4, location = GeoPoint(21.0285, 105.8542)),
    )

    @Test
    fun `verified motorcycle capabilities cover both transmissions`() {
        // Xe cua khach la MANUAL, con skill cua tai xe chi ghi AUTO. Voi xe may, hop so
        // khong con la dieu kien loai tru; bang lai moi la rang buoc that (A/A1 lai duoc ca hai).
        stub(driver())

        val ranked = service.filterAndRankDrivers(
            nearbyDrivers = nearby,
            requiredVehicleType = VehicleType.MOTORCYCLE,
            requiredTransmission = Transmission.MANUAL,
            radiusKm = 2.0,
        )

        assertEquals(listOf(2L), ranked.map { it.driverId })
    }

    @Test
    fun `bang B1 khong nhan chuyen xe o to so san`() {
        stub(
            driver(vehicleType = VehicleType.CAR, licenseClass = "B1"),
        )

        val ranked = service.filterAndRankDrivers(
            nearbyDrivers = nearby,
            requiredVehicleType = VehicleType.CAR,
            requiredTransmission = Transmission.MANUAL,
            radiusKm = 2.0,
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `driver without an eKYC projection cannot enter the candidate list`() {
        stub(driver(), verified = false)

        val ranked = service.filterAndRankDrivers(
            nearbyDrivers = nearby,
            requiredVehicleType = VehicleType.MOTORCYCLE,
            requiredTransmission = Transmission.AUTO,
            radiusKm = 2.0,
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `tai xe offline hoac het han bang lai khong phai ung vien`() {
        stub(driver().apply { status = DriverStatus.OFFLINE })

        assertTrue(
            service.filterAndRankDrivers(
                nearbyDrivers = nearby,
                requiredVehicleType = VehicleType.MOTORCYCLE,
                requiredTransmission = Transmission.AUTO,
                radiusKm = 2.0,
            ).isEmpty(),
        )

        stub(
            driver().apply { licenseExpiryDate = LocalDate.now().minusDays(1) },
        )

        assertTrue(
            service.filterAndRankDrivers(
                nearbyDrivers = nearby,
                requiredVehicleType = VehicleType.MOTORCYCLE,
                requiredTransmission = Transmission.AUTO,
                radiusKm = 2.0,
            ).isEmpty(),
        )
    }

    @Test
    fun `distance mode asks the nearest eligible driver before a better rated far driver`() {
        val near = driver(id = 2).apply { averageRating = 3.0; yearsOfExperience = 0 }
        val far = driver(id = 3).apply { averageRating = 5.0; yearsOfExperience = 10 }
        Mockito.`when`(driverRepository.findAllById(listOf(2L, 3L))).thenReturn(listOf(near, far))
        stubProjection(near)
        stubProjection(far)

        val ranked = service.filterAndRankDrivers(
            nearbyDrivers = listOf(
                NearbyDriver(driverId = 2, distanceKm = 0.3, location = GeoPoint(21.0285, 105.8542)),
                NearbyDriver(driverId = 3, distanceKm = 4.5, location = GeoPoint(21.05, 105.87)),
            ),
            requiredVehicleType = VehicleType.MOTORCYCLE,
            requiredTransmission = Transmission.MANUAL,
            radiusKm = 5.0,
        )

        assertEquals(listOf(2L, 3L), ranked.map { it.driverId })
    }

    private fun stub(driver: Driver, verified: Boolean = true) {
        Mockito.`when`(driverRepository.findAllById(listOf(driver.id))).thenReturn(listOf(driver))
        if (verified) stubProjection(driver)
    }

    private fun stubProjection(driver: Driver) {
        val entitlement = LicenseEntitlement(driver.licenseClass!!, LicenseValidity.HAS_EXPIRY, driver.licenseExpiryDate)
        val groups = supportedCapabilityGroups.filter { group -> group.vehicleType == driver.onlineVehicleType &&
            (driver.licenseClass != "B1" || group.transmission == Transmission.AUTO) }
        val applicationId = java.util.UUID.randomUUID()
        val capabilities = groups.map { ApprovedCapability(it.id, applicationId, 1, 2,
            LicenseEligibilityPolicy.VERSION, listOf(entitlement)) }
        Mockito.`when`(projection.current(driver.firebaseUid)).thenReturn(DriverVerificationProjection(
            VerificationStatus.APPROVED, listOf(entitlement), revision = 2, capabilities = capabilities,
            enabledGroups = capabilities.mapTo(mutableSetOf()) { it.groupId },
        ))
    }

    private fun driver(
        id: Long = 2,
        vehicleType: VehicleType = VehicleType.MOTORCYCLE,
        licenseClass: String = "A",
    ) = Driver().apply {
        creditBalance = 50_000
        this.id = id
        firebaseUid = "selection-$id"
        status = DriverStatus.ONLINE
        onlineVehicleType = vehicleType
        verificationStatus = "APPROVED"
        isFlagged = false
        this.licenseClass = licenseClass
        licenseExpiryDate = LocalDate.now().plusYears(5)
        averageRating = 4.8
        yearsOfExperience = 3
    }
}
