package com.nhom15.datxebackend.rating

import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.notification.api.DeliveryResult
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.NotificationPayload
import com.nhom15.datxebackend.rating.dto.CreateRideRatingRequest
import com.nhom15.datxebackend.rating.service.RideRatingService
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.Optional

/** App tài xế không nhận push "đánh giá mới": chỉ khách hàng nhận khi tài xế đánh giá. */
class RatingNotificationSuppressionTests {
    private val customer = AuthUser(11, "customer-uid", Role.CUSTOMER, "Khách", null, null, "ACTIVE")
    private val driver = AuthUser(7, "driver-uid", Role.DRIVER, "Tài", null, null, "ACTIVE")

    private data class Sent(val to: Recipient, val payload: NotificationPayload)

    private fun completedRide() = Ride(
        customerId = 11, driverId = 7, vehicleId = 2, type = RideType.INSTANT,
        pickup = Location(), dropoff = Location(), vehicleType = VehicleType.CAR,
        transmission = Transmission.AUTO, fareEstimate = 30_000, paymentMethod = PaymentMethod.CASH,
    ).apply {
        id = 12
        status = RideStatus.COMPLETED
        completedAt = Instant.now()
    }

    /** Không dùng Mockito matchers: mọi tham số Kotlin đều non-null. */
    private fun serviceWith(ride: Ride): Pair<RideRatingService, MutableList<Sent>> {
        val rides = mock(RideRepository::class.java) { inv ->
            if (inv.method.name == "findById") Optional.of(ride) else null
        }
        val jdbc = mock(JdbcTemplate::class.java) { inv ->
            when (inv.method.name) {
                "queryForObject" -> 0 // chưa có đánh giá nào cho ride này
                "update" -> 1
                else -> null
            }
        }
        val sent = mutableListOf<Sent>()
        val notifications = mock(NotificationPort::class.java) { inv ->
            sent += Sent(inv.getArgument<Recipient>(0), inv.getArgument<NotificationPayload>(1))
            DeliveryResult.SENT_WS
        }
        return RideRatingService(rides, jdbc, notifications) to sent
    }

    @Test
    fun `customer rating a driver saves the rating but never pushes to the driver`() {
        val (service, sent) = serviceWith(completedRide())

        val response = service.rate(customer, 12, CreateRideRatingRequest(4, "Ổn"))

        assertEquals("CUSTOMER_TO_DRIVER", response.direction)
        assertEquals(4, response.stars)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `driver rating a customer pushes only to the customer`() {
        val (service, sent) = serviceWith(completedRide())

        val response = service.rate(driver, 12, CreateRideRatingRequest(5, "Tốt"))

        assertEquals("DRIVER_TO_CUSTOMER", response.direction)
        assertEquals(1, sent.size)
        assertEquals(Recipient.Customer(11), sent.single().to)
    }
}
