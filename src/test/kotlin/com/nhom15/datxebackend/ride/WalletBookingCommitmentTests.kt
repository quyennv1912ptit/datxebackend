package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.port.CustomerVehicleQueryPort
import com.nhom15.datxebackend.common.port.CustomerVehicleSnapshot
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.payment.service.WalletService
import com.nhom15.datxebackend.pricing.api.RideQuoteProvider
import com.nhom15.datxebackend.pricing.model.RideQuote
import com.nhom15.datxebackend.ride.dto.request.CreateRideRequest
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
import com.nhom15.datxebackend.ride.repository.RideCreateIdempotencyRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.RideService
import com.nhom15.datxebackend.ride.service.RideCreationValidator
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.routing.model.PolylineCodec
import com.nhom15.datxebackend.routing.model.SavedRideRoute
import com.nhom15.datxebackend.routing.service.RideRouteService
import com.nhom15.datxebackend.voucher.service.VoucherService
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

/** Ví phải chịu cả số tiền đã cam kết ở các chuyến cũ khi kiểm tra hạn mức lúc đặt chuyến. */
class WalletBookingCommitmentTests {
    @Test
    fun `wallet booking checks payable fare plus committed wallet rides`() {
        val customerId = 21L
        val vehicleId = 31L
        val actor = AuthUser(customerId, "wallet-customer", Role.CUSTOMER, null, null, null, "ACTIVE")
        val pickup = GeoPoint(21.03, 105.84)
        val dropoff = GeoPoint(21.04, 105.85)
        val request = CreateRideRequest(
            vehicleId = vehicleId,
            type = RideType.SCHEDULED,
            scheduledAt = Instant.now().plusSeconds(3_600),
            pickupAddress = "Đón", pickupLat = pickup.lat, pickupLng = pickup.lng,
            dropoffAddress = "Trả", dropoffLat = dropoff.lat, dropoffLng = dropoff.lng,
            paymentMethod = PaymentMethod.WALLET,
        )

        val rides = mock(RideRepository::class.java)
        val committedStatuses = ACTIVE_RIDE_STATUSES + RideStatus.PENDING
        `when`(rides.sumCommittedWalletFare(customerId, committedStatuses, PaymentMethod.WALLET)).thenReturn(40_000L)
        `when`(rides.saveAndFlush(any())).thenAnswer { inv ->
            val saved = inv.getArgument<Ride>(0)
            saved.apply { id = 901L }
        }
        val customers = mock(CustomerRepository::class.java)
        `when`(customers.findByIdForUpdate(customerId)).thenReturn(Optional.of(Customer()))
        val vehicles = mock(CustomerVehicleQueryPort::class.java)
        `when`(vehicles.findOwnedVehicle(vehicleId, customerId)).thenReturn(
            CustomerVehicleSnapshot(vehicleId, customerId, Transmission.AUTO, VehicleType.CAR, "30A-12345"),
        )
        // RideQuoteProvider là fun interface: dùng lambda thay vì matcher (Mockito 5.23
        // trả null cho any()/eq() trên kiểu tham chiếu, vi phạm null-check của Kotlin).
        val quotes = RideQuoteProvider {
            RideQuote(1L, BigDecimal.TEN, 10L, 0L, 0L, 0L, 0L, 0L, 100_000L)
        }
        val routes = mock(RideRouteService::class.java)
        `when`(routes.resolve(customerId, vehicleId, VehicleType.CAR, pickup, dropoff, null))
            .thenReturn(SavedRideRoute("route-1", PolylineCodec.encode(listOf(pickup, dropoff)), 2_000.0, 300.0, "TEST", VehicleType.CAR))
        val wallets = mock(WalletService::class.java)
        val service = RideService(
            rides, vehicles, quotes, routes, mock(RideStateMachine::class.java),
            mock(VoucherService::class.java), mock(RideCreationValidator::class.java), customers,
            mock(RideCreateIdempotencyRepository::class.java), mock(RedisRateLimiter::class.java), wallets,
        )

        service.createRide(actor, request)

        verify(rides).sumCommittedWalletFare(customerId, committedStatuses, PaymentMethod.WALLET)
        verify(wallets).checkBooking(customerId, 140_000L)
    }
}
