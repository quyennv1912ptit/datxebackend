package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.chat.service.ChatService
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.*
import com.nhom15.datxebackend.ride.service.RideQueryService
import com.nhom15.datxebackend.ride.service.RideResponseMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import tools.jackson.databind.json.JsonMapper
import java.util.Optional

class RideDriverProfileTests {
    private fun driver() = Driver().apply {
        id = 7; fullName = "Nguyễn Văn An"; phoneNumber = "+84912345678"
        avatarUrl = "https://images.test/real-avatar.jpg"; averageRating = 4.7
        yearsOfExperience = 8; licenseClass = "B2"; licenseVerified = true; verificationStatus = "APPROVED"
        licenseNumber = "private-license"; bankAccountNumber = "private-bank"; firebaseUid = "private-uid"
    }
    private fun ride() = Ride(
        customerId = 11, driverId = 7, vehicleId = 2, type = RideType.INSTANT,
        pickup = Location(), dropoff = Location(), vehicleType = VehicleType.CAR,
        transmission = Transmission.AUTO, fareEstimate = 30_000, paymentMethod = PaymentMethod.CASH,
    ).apply { id = 12 }

    @Test
    fun `public driver projection contains actual profile data and no private documents or banking`() {
        val profile = RideResponseMapper.driver(driver())
        assertEquals("Nguyễn Văn An", profile.fullName)
        assertEquals("+84912345678", profile.phoneNumber)
        assertEquals("https://images.test/real-avatar.jpg", profile.avatarUrl)
        assertEquals(4.7, profile.averageRating)
        assertEquals(8, profile.yearsOfExperience)
        assertEquals("B2", profile.licenseClass)
        assertTrue(profile.licenseVerified)
        assertTrue(profile.profileVerified)
        val json = JsonMapper.builder().build().writeValueAsString(profile)
        for (sensitive in listOf("private-license", "private-bank", "private-uid", "licenseNumber", "firebaseUid", "bankAccountNumber")) {
            assertFalse(json.contains(sensitive), sensitive)
        }
    }

    @Test
    fun `missing experience and unverified profiles do not become sample values or verified claims`() {
        val driver = driver().apply { yearsOfExperience = null; averageRating = null; licenseVerified = null; verificationStatus = "PENDING" }
        val profile = RideResponseMapper.driver(driver)
        assertNull(profile.yearsOfExperience)
        assertNull(profile.averageRating)
        assertFalse(profile.licenseVerified)
        assertFalse(profile.profileVerified)
        driver.yearsOfExperience = 0
        assertEquals(0, RideResponseMapper.driver(driver).yearsOfExperience)
        driver.yearsOfExperience = -1
        assertNull(RideResponseMapper.driver(driver).yearsOfExperience)
    }

    private val rides = Mockito.mock(RideRepository::class.java)
    private val history = Mockito.mock(RideStatusHistoryRepository::class.java)
    private val reports = Mockito.mock(VehicleHandoverReportRepository::class.java)
    private val photos = Mockito.mock(HandoverPhotoRepository::class.java)
    private val chat = Mockito.mock(ChatService::class.java)
    private val customers = Mockito.mock(CustomerRepository::class.java)
    private val drivers = Mockito.mock(DriverRepository::class.java)
    private val service = RideQueryService(rides, history, reports, photos, chat, customers, drivers, Mockito.mock(com.nhom15.datxebackend.payment.service.PaymentService::class.java))
    private val owner = AuthUser(11, "customer", Role.CUSTOMER, "Khách", null, null, "ACTIVE")

    @Test
    fun `own ride detail includes only the assigned driver and unassigned ride has no profile`() {
        val ride = ride()
        Mockito.`when`(rides.findById(12)).thenReturn(Optional.of(ride))
        Mockito.`when`(history.findAllByRideIdOrderByCreatedAtAscIdAsc(12)).thenReturn(emptyList())
        Mockito.`when`(customers.findById(11)).thenReturn(Optional.empty())
        Mockito.`when`(drivers.findById(7)).thenReturn(Optional.of(driver()))
        val result = service.getDetail(owner, 12)
        assertEquals(7L, result.driver?.id)
        assertEquals(8, result.driver?.yearsOfExperience)
        ride.driverId = null
        Mockito.clearInvocations(drivers)
        assertNull(service.getDetail(owner, 12).driver)
        Mockito.verifyNoInteractions(drivers)
    }

    @Test
    fun `customer email is exposed only to the driver assigned to an active trip`() {
        val ride = ride().apply { status = RideStatus.DRIVER_ASSIGNED }
        val customer = Customer().apply { email = "customer@example.test" }
        Mockito.`when`(rides.findById(12)).thenReturn(Optional.of(ride))
        Mockito.`when`(customers.findById(11)).thenReturn(Optional.of(customer))
        val assignedDriver = owner.copy(id = 7, role = Role.DRIVER)

        assertEquals(customer.email, service.getDetail(assignedDriver, 12).customer?.email)
        assertNull(service.getDetail(owner, 12).customer?.email)
        assertThrows(ForbiddenException::class.java) { service.getDetail(assignedDriver.copy(id = 8), 12) }
        for (status in listOf(RideStatus.SEARCHING, RideStatus.COMPLETED)) {
            ride.status = status
            assertNull(service.getDetail(assignedDriver, 12).customer?.email)
        }
    }

    @Test
    fun `unrelated customer cannot fetch driver contact or even trigger a driver lookup`() {
        Mockito.`when`(rides.findById(12)).thenReturn(Optional.of(ride()))
        assertThrows(ForbiddenException::class.java) { service.getDetail(owner.copy(id = 99), 12) }
        Mockito.verifyNoInteractions(drivers, customers, history, reports, photos, chat)
    }
}
