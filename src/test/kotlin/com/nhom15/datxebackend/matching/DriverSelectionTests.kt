package com.nhom15.datxebackend.matching

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.port.NearbyDriver
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.matching.service.DriverSelectionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.LocalDate

class DriverSelectionTests {
    private val driverRepository = Mockito.mock(DriverRepository::class.java)
    private val skillRepository = Mockito.mock(DriverSkillRepository::class.java)
    private val service = DriverSelectionService(driverRepository, skillRepository, MatchingProperties())

    private val nearby = listOf(
        NearbyDriver(driverId = 2, distanceKm = 0.4, location = GeoPoint(21.0285, 105.8542)),
    )

    @Test
    fun `ky nang da duyet theo loai xe la du, khong phu thuoc hop so cua xe khach`() {
        // Xe cua khach la MANUAL, con skill cua tai xe chi ghi AUTO. Voi xe may, hop so
        // khong con la dieu kien loai tru; bang lai moi la rang buoc that (A/A1 lai duoc ca hai).
        stub(driver(), verifiedDriverIds = setOf(2L))

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
            verifiedDriverIds = setOf(2L),
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
    fun `tai xe chua duoc duyet ky nang khong phai ung vien`() {
        stub(driver(), verifiedDriverIds = emptySet())

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
        stub(driver().apply { status = DriverStatus.OFFLINE }, verifiedDriverIds = setOf(2L))

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
            verifiedDriverIds = setOf(2L),
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
        Mockito.`when`(
            skillRepository.findVerifiedDriverIds(listOf(2L, 3L), VehicleType.MOTORCYCLE),
        ).thenReturn(setOf(2L, 3L))

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

    private fun stub(driver: Driver, verifiedDriverIds: Set<Long>) {
        // Không dùng Mockito matcher cho tham số non-null của Kotlin (Kotlin chèn null-check
        // vào giá trị trả về của any()); stub bằng đúng giá trị service truyền xuống.
        Mockito.`when`(driverRepository.findAllById(listOf(driver.id))).thenReturn(listOf(driver))
        Mockito.`when`(
            skillRepository.findVerifiedDriverIds(listOf(driver.id), driver.onlineVehicleType!!),
        ).thenReturn(verifiedDriverIds)
    }

    private fun driver(
        id: Long = 2,
        vehicleType: VehicleType = VehicleType.MOTORCYCLE,
        licenseClass: String = "A",
    ) = Driver().apply {
        creditBalance = 50_000
        this.id = id
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
