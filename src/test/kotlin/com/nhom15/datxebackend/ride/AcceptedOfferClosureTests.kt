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
import com.nhom15.datxebackend.ride.repository.DispatchOffer
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.repository.RideStatusHistoryRepository
import com.nhom15.datxebackend.ride.repository.VehicleHandoverReportRepository
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.util.Optional

class AcceptedOfferClosureTests {
    @Test
    fun `winning the database cas closes the offer as accepted`() {
        val rides = mock(RideRepository::class.java)
        val drivers = mock(DriverRepository::class.java)
        val offers = mock(DispatchOfferRepository::class.java)
        val current = Ride(
            customerId = 1, vehicleId = 2, type = RideType.INSTANT,
            pickup = Location(GeoPoint(21.03, 105.84), "Đón"),
            dropoff = Location(GeoPoint(21.04, 105.85), "Đến"),
            vehicleType = VehicleType.CAR, transmission = Transmission.AUTO,
            fareEstimate = 30000, paymentMethod = PaymentMethod.CASH,
            status = RideStatus.SEARCHING,
        ).apply {
            id = 42
            searchExpiresAt = Instant.now().plusSeconds(90)
            redispatchCount = 1
        }
        `when`(rides.assignDriverIfSearching(42, 7)).thenReturn(1)
        `when`(rides.findById(42)).thenReturn(Optional.of(current))
        `when`(drivers.findByIdForUpdate(7)).thenReturn(Optional.of(
            com.nhom15.datxebackend.driver.entity.Driver().apply {
                firebaseUid = "driver7"
                status = DriverStatus.ONLINE
            },
        ))
        `when`(offers.outstanding(42)).thenReturn(
            DispatchOffer("offer-1", 42, 7, Instant.now().plusSeconds(10), 1),
        )
        val service = RideStateMachine(
            rideRepository = rides,
            historyRepository = mock(RideStatusHistoryRepository::class.java),
            eventPublisher = mock(EventPublisher::class.java),
            reportRepository = mock(VehicleHandoverReportRepository::class.java),
            conversationService = mock(com.nhom15.datxebackend.chat.service.ConversationService::class.java),
            matching = MatchingProperties(),
            dispatchOutbox = mock(RideDispatchOutboxRepository::class.java),
            dispatchOffers = offers,
            drivers = drivers,
            wallets = mock(com.nhom15.datxebackend.payment.service.WalletService::class.java),
            vouchers = mock(com.nhom15.datxebackend.voucher.service.VoucherService::class.java),
            scheduled = ScheduledRideProperties(),
            clock = Clock.systemUTC(),
            verification = mock(DriverVerificationEligibility::class.java) { invocation ->
                if (invocation.method.name == "mayDriveForRide") true else RETURNS_DEFAULTS.answer(invocation)
            },
        )

        assertNotNull(service.tryAssignDriver(42, 7))

        verify(offers).close("offer-1", "ACCEPTED", null, null)
    }
}
