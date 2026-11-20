package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.port.VehicleBookingDetails
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.vehicle.catalog.VehicleCatalog
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.Optional
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.matching.service.DriverSelectionService
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.springframework.data.redis.core.StringRedisTemplate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriverRideEligibilityTest {
    private val source = mock(VerificationProjectionRepository::class.java)
    private val sync = mock(VerificationSync::class.java)
    private val eligibility = DriverVerificationEligibility(source, sync)
    private val application = UUID.randomUUID()
    private val head = VerificationHead(UUID.randomUUID(), 2)
    private val now = Instant.parse("2026-11-16T15:00:00Z")
    private val tomorrowInVietnam = Instant.parse("2026-11-16T17:30:00Z")
    private val driver = Driver().apply { firebaseUid = "synthetic"; status = DriverStatus.ONLINE }
    private val motorcycle = LicenseEntitlement("A1", LicenseValidity.NO_EXPIRY, null)
    private val car = LicenseEntitlement("B", LicenseValidity.HAS_EXPIRY, LocalDate.of(2026, 11, 16))
    private val record = DriverVerificationProjection(
        VerificationStatus.APPROVED, listOf(motorcycle, car), revision = 4,
        capabilities = listOf(
            ApprovedCapability("car-auto", application, 3, 4, LicenseEligibilityPolicy.VERSION, listOf(car)),
            ApprovedCapability("moto-50-125", application, 3, 4, LicenseEligibilityPolicy.VERSION, listOf(motorcycle)),
        ), enabledGroups = setOf("car-auto", "moto-50-125"),
    )

    private fun ride(type: VehicleType, transmission: Transmission, group: String?, schedule: Instant? = null) =
        Ride(customerId = 11, vehicleId = 4, fareEstimate = 1000, vehicleType = type, transmission = transmission,
            pickup = Location(GeoPoint(21.0, 105.0), "Đón"), dropoff = Location(GeoPoint(21.1, 105.2), "Đến"),
            type = if (schedule == null) RideType.INSTANT else RideType.SCHEDULED,
            paymentMethod = PaymentMethod.CASH, scheduledAt = schedule,
            vehicleBookingSnapshot = group?.let { VehicleBookingDetails(
                null, null, null, null, null, null, null, null, null, "CUSTOMER_DECLARED",
                it, "CUSTOMER_DECLARED", VehicleCatalog.VERSION,
            ) },
        )

    private fun stub(value: DriverVerificationProjection) {
        `when`(source.synchronizedHead()).thenReturn(head)
        `when`(sync.confirms(head)).thenReturn(true)
        `when`(source.current(driver.firebaseUid)).thenReturn(value)
    }

    private fun permits(ride: Ride, starting: Boolean = false, at: Instant = now) =
        eligibility.mayDriveForRide(driver, ride, starting = starting, now = at)

    @Test
    fun `exact immutable group selects correct entitlement and does not widen legacy or mismatched trips`() {
        stub(record)
        driver.onlineVehicleType = VehicleType.MOTORCYCLE
        assertTrue(permits(ride(VehicleType.MOTORCYCLE, Transmission.AUTO, "moto-50-125")))
        assertFalse(permits(ride(VehicleType.MOTORCYCLE, Transmission.AUTO, "moto-electric-4-11")))
        assertFalse(permits(ride(VehicleType.MOTORCYCLE, Transmission.AUTO, null)))
        driver.onlineVehicleType = VehicleType.CAR
        assertTrue(permits(ride(VehicleType.CAR, Transmission.AUTO, "car-auto")))
        assertFalse(permits(ride(VehicleType.CAR, Transmission.MANUAL, "car-manual")))
        assertFalse(permits(ride(VehicleType.CAR, Transmission.MANUAL, "car-auto")))
    }

    @Test
    fun `scheduled eligibility uses Vietnamese pickup day and checks again before starting`() {
        stub(record)
        driver.onlineVehicleType = VehicleType.CAR
        assertFalse(permits(ride(VehicleType.CAR, Transmission.AUTO, "car-auto", tomorrowInVietnam)))
        val instant = ride(VehicleType.CAR, Transmission.AUTO, "car-auto")
        driver.status = DriverStatus.BUSY
        assertTrue(permits(instant, starting = true))
        assertFalse(permits(instant, starting = true, at = tomorrowInVietnam))
        driver.onlineVehicleType = VehicleType.MOTORCYCLE
        assertTrue(permits(ride(VehicleType.MOTORCYCLE, Transmission.AUTO, "moto-50-125", tomorrowInVietnam), starting = true))
    }

    @Test
    fun `pending revoked disabled or unsynchronized evidence rejects an otherwise identical trip`() {
        driver.onlineVehicleType = VehicleType.CAR
        val trip = ride(VehicleType.CAR, Transmission.AUTO, "car-auto")
        for (change in listOf(record.copy(status = VerificationStatus.SUBMITTED),
            record.copy(status = VerificationStatus.REVOKED), record.copy(enabledGroups = setOf("moto-50-125")),
            record.copy(capabilities = emptyList()), record.copy(cccdExpiresOn = LocalDate.of(2026, 11, 15))
        )) {
            stub(change)
            assertFalse(permits(trip))
        }
        stub(record)
        `when`(sync.confirms(head)).thenReturn(false)
        assertFalse(permits(trip))
    }
    @Test
    fun `offer recheck refuses to write when preference goes off after candidate selection`() {
        val trip = ride(VehicleType.CAR, Transmission.AUTO, "car-auto").apply { id = 91 }
        trip.status = com.nhom15.datxebackend.common.enums.RideStatus.SEARCHING
        driver.id = 7
        driver.onlineVehicleType = VehicleType.CAR
        driver.creditBalance = 75_000
        val drivers = mock(DriverRepository::class.java)
        val rides = mock(RideRepository::class.java)
        `when`(drivers.findByIdForUpdate(7)).thenReturn(Optional.of(driver))
        `when`(rides.findByIdForUpdate(91)).thenReturn(Optional.of(trip))
        val selection = DriverSelectionService(drivers, MatchingProperties(),
            verification = eligibility, redis = mock(StringRedisTemplate::class.java), rides = rides)
        stub(record.copy(enabledGroups = emptySet()))
        assertFalse(selection.recordOfferIfEligible(trip, 7) { error("Record must not be called") })
    }
}

