package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.*
import com.nhom15.datxebackend.common.event.EventPublisher
import com.nhom15.datxebackend.common.exception.*
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.ride.dto.request.*
import com.nhom15.datxebackend.ride.entity.*
import com.nhom15.datxebackend.ride.repository.*
import com.nhom15.datxebackend.ride.service.*
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.Optional

class HandoverCorrectionTests {
    private val ride = Ride(customerId = 11, driverId = 7, vehicleId = 2, type = RideType.INSTANT, pickup = Location(), dropoff = Location(), vehicleType = VehicleType.CAR, transmission = Transmission.AUTO, fareEstimate = 30000, paymentMethod = PaymentMethod.CASH).apply { id = 12; status = RideStatus.VEHICLE_HANDOVER }
    private val report = VehicleHandoverReport(ride, HandoverPhase.PICKUP, 15000).apply { id = 9 }
    private val rides = Mockito.mock(RideRepository::class.java)
    private val reports = Mockito.mock(VehicleHandoverReportRepository::class.java)
    private val photos = Mockito.mock(HandoverPhotoRepository::class.java)
    private val assets = Mockito.mock(CloudinaryAssetService::class.java)
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val mapper = jacksonObjectMapper()
    private val validation = RideHandoverService(rides, reports, photos, Mockito.mock(RideStateMachine::class.java), Mockito.mock(EventPublisher::class.java), assets)
    private val service = HandoverCorrectionService(rides, reports, photos, validation, assets, jdbc, mapper)
    private val customer = AuthUser(11, "customer", Role.CUSTOMER, "Khách", null, null, "ACTIVE")
    private val driver = customer.copy(id = 7, role = Role.DRIVER)
    init {
        Mockito.`when`(rides.findByIdForUpdate(12)).thenReturn(Optional.of(ride))
        Mockito.`when`(rides.findById(12)).thenReturn(Optional.of(ride))
        Mockito.`when`(reports.findByRideIdAndPhase(12, HandoverPhase.PICKUP)).thenReturn(report)
        Mockito.`when`(photos.findAllByReportIdOrderByCreatedAtAsc(9)).thenReturn(emptyList())
    }
    @Test fun `correction freezes confirmation until a new matching revision is reviewed`() {
        val result = service.request(customer, 12, HandoverPhase.PICKUP, HandoverCorrectionRequest(1, "Số km ghi chưa đúng"))
        assertEquals("Số km ghi chưa đúng", result.correctionReason)
        assertThrows(ConflictException::class.java) { validation.confirmPickup(customer, 12, ConfirmHandoverRequest(true, 1)) }
        assertFalse(report.confirmedByCustomer)
        assertThrows(ConflictException::class.java) { service.request(customer, 12, HandoverPhase.PICKUP, HandoverCorrectionRequest(2, "Số km ghi chưa đúng")) }
        assertThrows(NotFoundException::class.java) { service.request(customer.copy(id = 99), 12, HandoverPhase.PICKUP, HandoverCorrectionRequest(1, "Số km ghi chưa đúng")) }
    }
    @Test fun `driver revision archives snapshot before replacing and cannot revise twice`() {
        report.correctionReason = "Số km ghi chưa đúng"
        val photoDrafts = listOf(HandoverPhotoAngle.FRONT, HandoverPhotoAngle.BACK, HandoverPhotoAngle.LEFT, HandoverPhotoAngle.RIGHT).map { HandoverPhotoRequest(it, "https://example.test/$it.jpg", "owned/$it") }
        val result = service.revise(driver, 12, HandoverPhase.PICKUP, ReviseHandoverRequest(1, CreateHandoverReportRequest(15100, photos = photoDrafts)))
        assertEquals(2, result.revision)
        assertNull(result.correctionReason)
        assertFalse(result.confirmedByCustomer)
        assertEquals(15100, result.odometer)
        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(jdbc).update(Mockito.anyString(), Mockito.eq(9L), Mockito.eq(1), captor.capture(), Mockito.eq("Số km ghi chưa đúng"), Mockito.eq("[]"))
        val snapshot = mapper.readValue(captor.value, com.nhom15.datxebackend.ride.dto.response.HandoverReportResponse::class.java)
        assertEquals(15000, snapshot.odometer)
        assertEquals("Số km ghi chưa đúng", snapshot.correctionReason)
        assertThrows(ConflictException::class.java) { validation.confirmPickup(customer, 12, ConfirmHandoverRequest(true, 1)) }
        validation.confirmPickup(customer, 12, ConfirmHandoverRequest(true, 2))
        assertTrue(report.confirmedByCustomer)
        assertThrows(ConflictException::class.java) { service.revise(driver, 12, HandoverPhase.PICKUP, ReviseHandoverRequest(2, CreateHandoverReportRequest(15200, photos = photoDrafts))) }
    }
    @Test fun `confirmed or completed handovers cannot be disputed retroactively`() {
        report.confirmedByCustomer = true
        assertThrows(ConflictException::class.java) { service.request(customer, 12, HandoverPhase.PICKUP, HandoverCorrectionRequest(1, "Tôi muốn thay đổi lại")) }
        report.confirmedByCustomer = false
        ride.status = RideStatus.IN_PROGRESS
        assertThrows(ConflictException::class.java) { service.request(customer, 12, HandoverPhase.PICKUP, HandoverCorrectionRequest(1, "Tôi muốn thay đổi lại")) }
    }
}
