package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.chat.service.ConversationService
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.event.EventPublisher
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.config.properties.ScheduledRideProperties
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.VehicleHandoverReportRepository
import com.nhom15.datxebackend.ride.event.RideStatusChanged
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.repository.RideStatusHistoryRepository
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.ride.service.state.RideTransitionCommand
import com.nhom15.datxebackend.ride.service.state.RideTransitionEvent
import com.nhom15.datxebackend.ride.service.state.driverRideActor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class RetryDispatchCleanupTests {
    private val now = Instant.parse("2026-11-22T10:00:00Z")

    private fun ride(redispatchCount: Int) = Ride(
        customerId = 1,
        vehicleId = 2,
        type = RideType.INSTANT,
        pickup = Location(GeoPoint(21.03, 105.84), "Đón"),
        dropoff = Location(GeoPoint(21.04, 105.85), "Đến"),
        vehicleType = VehicleType.CAR,
        transmission = Transmission.AUTO,
        fareEstimate = 30_000,
        paymentMethod = PaymentMethod.CASH,
        status = RideStatus.DRIVER_ASSIGNED,
    ).apply {
        id = 42
        driverId = 7
        acceptedAt = now.minusSeconds(30)
        searchExpiresAt = now.plusSeconds(60)
        this.redispatchCount = redispatchCount
    }

    private fun machine(
        rides: RideRepository,
        events: EventPublisher,
        outbox: RideDispatchOutboxRepository,
    ) = RideStateMachine(
        rideRepository = rides,
        historyRepository = Mockito.mock(RideStatusHistoryRepository::class.java),
        eventPublisher = events,
        reportRepository = Mockito.mock(VehicleHandoverReportRepository::class.java),
        conversationService = Mockito.mock(ConversationService::class.java),
        matching = MatchingProperties(),
        dispatchOutbox = outbox,
        dispatchOffers = Mockito.mock(DispatchOfferRepository::class.java),
        drivers = Mockito.mock(DriverRepository::class.java),
        wallets = Mockito.mock(com.nhom15.datxebackend.payment.service.WalletService::class.java),
        vouchers = Mockito.mock(com.nhom15.datxebackend.voucher.service.VoucherService::class.java),
        scheduled = ScheduledRideProperties(),
        clock = Clock.fixed(now, ZoneOffset.UTC),
        verification = Mockito.mock(DriverVerificationEligibility::class.java),
    )

    @Test
    fun `terminal NO_DRIVER_FOUND detaches the cancelled driver and records redispatch audit`() {
        val rides = Mockito.mock(RideRepository::class.java)
        val events = Mockito.mock(EventPublisher::class.java)
        val outbox = Mockito.mock(RideDispatchOutboxRepository::class.java)
        val current = ride(redispatchCount = 2)
        val expiryBefore = current.searchExpiresAt
        Mockito.`when`(rides.findByIdForUpdate(42L)).thenReturn(Optional.of(current))
        Mockito.`when`(rides.compareAndSetStatus(Mockito.eq(42L), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
            .thenReturn(1)

        val result = machine(rides, events, outbox).transition(
            RideTransitionCommand(42L, RideTransitionEvent.CANCEL, driverRideActor(7L), cancelReason = "Không thể đón"),
        )

        assertEquals(RideStatus.NO_DRIVER_FOUND, result.status)
        assertNull(result.driverId)
        assertNull(result.acceptedAt)
        assertEquals(7L, result.lastRedispatchDriverId)
        assertEquals("Không thể đón", result.lastRedispatchReason)
        assertNotNull(result.lastRedispatchAt)
        assertEquals(2, result.redispatchCount, "terminal retry must not increment the generation")
        assertEquals(expiryBefore, result.searchExpiresAt, "terminal retry must not extend the search")
        Mockito.verify(outbox, Mockito.never()).schedule(42L)

        val published = Mockito.mockingDetails(events).invocations
            .map { it.arguments.firstOrNull() }
            .filterIsInstance<RideStatusChanged>()
            .single()
        assertEquals(7L, published.driverId)
    }

    @Test
    fun `SEARCHING retry still increments generation and refreshes expiry`() {
        val rides = Mockito.mock(RideRepository::class.java)
        val outbox = Mockito.mock(RideDispatchOutboxRepository::class.java)
        val current = ride(redispatchCount = 1)
        val expiryBefore = current.searchExpiresAt
        Mockito.`when`(rides.findByIdForUpdate(42L)).thenReturn(Optional.of(current))
        Mockito.`when`(rides.compareAndSetStatus(Mockito.eq(42L), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong()))
            .thenReturn(1)

        val result = machine(rides, Mockito.mock(EventPublisher::class.java), outbox).transition(
            RideTransitionCommand(42L, RideTransitionEvent.CANCEL, driverRideActor(7L), cancelReason = "Không thể đón"),
        )

        assertEquals(RideStatus.SEARCHING, result.status)
        assertNull(result.driverId)
        assertNull(result.acceptedAt)
        assertEquals(7L, result.lastRedispatchDriverId)
        assertEquals(2, result.redispatchCount)
        assertNotEquals(expiryBefore, result.searchExpiresAt)
        assertEquals(now.plus(MatchingProperties().searchTimeout), result.searchExpiresAt)
        Mockito.verify(outbox).schedule(42L)
    }
}
