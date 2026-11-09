package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.config.properties.ScheduledRideProperties
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.ScheduledRideScheduler
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.ride.service.state.RideTransitionCommand
import com.nhom15.datxebackend.ride.service.state.RideTransitionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Clock
import java.time.Instant
import java.util.Optional

class ScheduledRideSchedulerTests {
    private val pickup = Instant.parse("2026-11-09T01:00:00Z")
    private val rides = mock(RideRepository::class.java)
    private val customers = mock(CustomerRepository::class.java)
    private val state = mock(RideStateMachine::class.java)
    private val transactions = mock(PlatformTransactionManager::class.java)
    private val clock = mock(Clock::class.java)
    private val scheduler = ScheduledRideScheduler(rides, state, customers, ScheduledRideProperties(), clock, transactions)

    init {
        `when`(transactions.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
        `when`(customers.findByIdForUpdate(1)).thenReturn(Optional.of(Customer()))
    }

    @Test fun `lead time starts at 07 45 and a cancelled candidate is ignored after acquiring the lock`() {
        val current = ride()
        scanAt(pickup.minusSeconds(901))
        verifyNoInteractions(customers, state)

        `when`(rides.findByIdForUpdate(42)).thenReturn(Optional.of(ride(RideStatus.CANCELLED)))
        scanAt(pickup.minusSeconds(900), current)
        verifyNoInteractions(state)

        `when`(rides.findByIdForUpdate(42)).thenReturn(Optional.of(current))
        scanAt(pickup.minusSeconds(900), current)
        assertEquals(RideTransitionEvent.START_SEARCH, transition())
    }

    @Test fun `a busy customer can activate a waiting schedule after pickup time but before the cutoff`() {
        val current = ride()
        `when`(rides.findByIdForUpdate(42)).thenReturn(Optional.of(current))
        `when`(rides.existsByCustomerIdAndStatusIn(1, ACTIVE_RIDE_STATUSES)).thenReturn(true, false)

        scanAt(pickup.minusSeconds(900), current)
        verifyNoInteractions(state)

        scanAt(pickup.plusSeconds(599), current)
        assertEquals(RideTransitionEvent.START_SEARCH, transition())
        val ordered = inOrder(customers, rides)
        ordered.verify(customers).findByIdForUpdate(1)
        ordered.verify(rides).findByIdForUpdate(42)
    }

    @Test fun `pending schedule expires at 08 10 even when the customer is busy`() {
        val current = ride()
        `when`(rides.findByIdForUpdate(42)).thenReturn(Optional.of(current))
        `when`(rides.existsByCustomerIdAndStatusIn(1, ACTIVE_RIDE_STATUSES)).thenReturn(true)

        scanAt(pickup.plusSeconds(600), current)
        assertEquals(RideTransitionEvent.EXPIRE, transition())
        verify(rides, never()).existsByCustomerIdAndStatusIn(1, ACTIVE_RIDE_STATUSES)
    }

    private fun scanAt(now: Instant, vararg candidates: Ride) {
        `when`(clock.instant()).thenReturn(now)
        `when`(rides.findByTypeAndStatusAndScheduledAtLessThanEqualOrderByScheduledAtAscIdAsc(
            RideType.SCHEDULED, RideStatus.PENDING, now.plusSeconds(900),
        )).thenReturn(candidates.toList())
        scheduler.processDueRides()
    }

    private fun transition(): RideTransitionEvent {
        val command = mockingDetails(state).invocations.single().arguments.single() as RideTransitionCommand
        assertEquals(42L, command.rideId)
        return command.event
    }

    private fun ride(status: RideStatus = RideStatus.PENDING) = Ride(
        customerId = 1, vehicleId = 2, type = RideType.SCHEDULED, scheduledAt = pickup, status = status,
        pickup = Location(GeoPoint(21.03, 105.84), "Đón"),
        dropoff = Location(GeoPoint(21.04, 105.85), "Đến"),
        vehicleType = VehicleType.CAR, transmission = Transmission.AUTO, fareEstimate = 30_000,
        paymentMethod = PaymentMethod.CASH,
    ).apply { id = 42 }
}
