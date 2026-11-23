package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.event.EventPublisher
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.config.properties.ScheduledRideProperties
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.repository.RideStatusHistoryRepository
import com.nhom15.datxebackend.ride.repository.VehicleHandoverReportRepository
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.voucher.service.VoucherService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.util.Optional

class AdminReviveVoucherTests {
    private fun ride(status: RideStatus, discount: Long) = Ride(
        customerId = 1, vehicleId = 2, type = RideType.INSTANT,
        pickup = Location(GeoPoint(21.03, 105.84), "Đón"),
        dropoff = Location(GeoPoint(21.04, 105.85), "Đến"),
        vehicleType = VehicleType.CAR, transmission = Transmission.AUTO,
        fareEstimate = 30000, paymentMethod = PaymentMethod.CASH,
        status = status,
    ).apply {
        id = 42
        discountAmount = discount
        finalFare = 27000L.takeIf { discount > 0 }
        searchExpiresAt = Instant.now().plusSeconds(90)
    }

    private fun driver() = com.nhom15.datxebackend.driver.entity.Driver().apply {
        firebaseUid = "driver7"
        status = DriverStatus.ONLINE
    }

    @Test
    fun `reviving a terminal no driver ride drops the already released voucher discount`() {
        val rides = mock(RideRepository::class.java)
        val drivers = mock(DriverRepository::class.java)
        val vouchers = mock(VoucherService::class.java)
        val current = ride(RideStatus.NO_DRIVER_FOUND, 3000)
        `when`(rides.findByIdForUpdate(42)).thenReturn(Optional.of(current))
        `when`(rides.compareAndSetStatus(42L, "NO_DRIVER_FOUND", "DRIVER_ASSIGNED", current.version)).thenReturn(1)
        `when`(drivers.findByIdForUpdate(7)).thenReturn(Optional.of(driver()))
        val service = machine(rides, drivers, vouchers)

        val (assigned, previous) = service.adminAssignDriver(42, 7, 99)

        assertEquals(RideStatus.NO_DRIVER_FOUND, previous)
        assertEquals(RideStatus.DRIVER_ASSIGNED, assigned.status)
        assertEquals(0L, current.discountAmount)
        assertNull(current.finalFare)
        verify(vouchers, never()).releaseForRide(42L)
    }

    @Test
    fun `reassigning a live assigned ride keeps its still held voucher discount`() {
        val rides = mock(RideRepository::class.java)
        val drivers = mock(DriverRepository::class.java)
        val vouchers = mock(VoucherService::class.java)
        val current = ride(RideStatus.DRIVER_ASSIGNED, 3000).apply { driverId = 7 }
        `when`(rides.findByIdForUpdate(42)).thenReturn(Optional.of(current))
        `when`(rides.compareAndSetStatus(42L, "DRIVER_ASSIGNED", "DRIVER_ASSIGNED", current.version)).thenReturn(1)
        `when`(drivers.findByIdForUpdate(8)).thenReturn(Optional.of(driver()))
        val service = machine(rides, drivers, vouchers)

        service.adminAssignDriver(42, 8, 99)

        assertEquals(3000L, current.discountAmount)
        verify(vouchers, never()).releaseForRide(42L)
    }

    private fun machine(
        rides: RideRepository,
        drivers: DriverRepository,
        vouchers: VoucherService,
    ) = RideStateMachine(
        rideRepository = rides,
        historyRepository = mock(RideStatusHistoryRepository::class.java),
        eventPublisher = mock(EventPublisher::class.java),
        reportRepository = mock(VehicleHandoverReportRepository::class.java),
        conversationService = mock(com.nhom15.datxebackend.chat.service.ConversationService::class.java),
        matching = MatchingProperties(),
        dispatchOutbox = mock(RideDispatchOutboxRepository::class.java),
        dispatchOffers = mock(DispatchOfferRepository::class.java),
        drivers = drivers,
        wallets = mock(com.nhom15.datxebackend.payment.service.WalletService::class.java),
        vouchers = vouchers,
        scheduled = ScheduledRideProperties(),
        clock = Clock.systemUTC(),
        verification = mock(DriverVerificationEligibility::class.java) { invocation ->
            if (invocation.method.name == "mayDriveForRide") true else RETURNS_DEFAULTS.answer(invocation)
        },
    )
}
