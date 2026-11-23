package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.common.domain.*
import com.nhom15.datxebackend.common.enums.*
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.event.PaymentUpdated
import com.nhom15.datxebackend.payment.service.*
import com.nhom15.datxebackend.ride.entity.*
import com.nhom15.datxebackend.ride.event.RideCompleted
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Optional

class PaymentSettlementTests {
    @Test fun `duplicate completion event does not charge twice`() {
        val jdbc = mock(JdbcTemplate::class.java)
        val rides = mock(RideRepository::class.java)
        val wallets = mock(WalletService::class.java)
        val ride = Ride(customerId = 1, vehicleId = 2, driverId = 7,
            type = RideType.INSTANT, pickup = Location(GeoPoint(21.0, 105.0), "Đón"),
            dropoff = Location(GeoPoint(21.1, 105.1), "Trả"), vehicleType = VehicleType.CAR,
            transmission = Transmission.AUTO, fareEstimate = 100_000, finalFare = 100_000,
            paymentMethod = PaymentMethod.WALLET, status = RideStatus.COMPLETED).apply { id = 42 }
        `when`(rides.findByIdForUpdate(42)).thenReturn(Optional.of(ride))
        `when`(jdbc.update("INSERT INTO ride_wallet_settlements(ride_id) VALUES (?) ON CONFLICT DO NOTHING", 42L))
            .thenReturn(1, 0)
        `when`(wallets.settle(42, 1, 7, 100_000, PaymentMethod.WALLET, 100_000))
            .thenReturn(PaymentMethod.WALLET)
        val service = PaymentService(jdbc, mock(DriverRepository::class.java), rides, wallets, mock(ApplicationEventPublisher::class.java))
        repeat(2) { service.onRideCompleted(RideCompleted(42, 1, 7, Money(100_000))) }
        verify(wallets, times(1)).settle(42, 1, 7, 100_000, PaymentMethod.WALLET, 100_000)
    }

    @Test fun `wallet to cash fallback publishes payment updated as pending`() {
        val jdbc = mock(JdbcTemplate::class.java)
        val rides = mock(RideRepository::class.java)
        val wallets = mock(WalletService::class.java)
        val events = mock(ApplicationEventPublisher::class.java)
        val ride = Ride(customerId = 1, vehicleId = 2, driverId = 7,
            type = RideType.INSTANT, pickup = Location(GeoPoint(21.0, 105.0), "Đón"),
            dropoff = Location(GeoPoint(21.1, 105.1), "Trả"), vehicleType = VehicleType.CAR,
            transmission = Transmission.AUTO, fareEstimate = 100_000, finalFare = 100_000,
            paymentMethod = PaymentMethod.WALLET, status = RideStatus.COMPLETED).apply { id = 43 }
        `when`(rides.findByIdForUpdate(43)).thenReturn(Optional.of(ride))
        `when`(jdbc.update("INSERT INTO ride_wallet_settlements(ride_id) VALUES (?) ON CONFLICT DO NOTHING", 43L))
            .thenReturn(1)
        `when`(wallets.settle(43, 1, 7, 100_000, PaymentMethod.WALLET, 100_000))
            .thenReturn(PaymentMethod.CASH)
        val service = PaymentService(jdbc, mock(DriverRepository::class.java), rides, wallets, events)

        service.onRideCompleted(RideCompleted(43, 1, 7, Money(100_000)))

        val captor = ArgumentCaptor.forClass(PaymentUpdated::class.java)
        verify(events).publishEvent(captor.capture())
        assertEquals(43L, captor.value.rideId)
        assertEquals(PaymentMethod.WALLET, captor.value.fromMethod)
        assertEquals(PaymentMethod.CASH, captor.value.method)
        assertEquals("PENDING", captor.value.status)
        assertEquals(100_000L, captor.value.amount)
        assertEquals(PaymentMethod.CASH, ride.paymentMethod)
    }
}
