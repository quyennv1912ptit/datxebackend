package com.nhom15.datxebackend.rating

import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.rating.service.RideRatingService
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.util.Optional

class CustomerReviewsTests {
    private fun ride() = Ride(
        customerId = 11, driverId = 7, vehicleId = 2, type = RideType.INSTANT,
        pickup = Location(), dropoff = Location(), vehicleType = VehicleType.CAR,
        transmission = Transmission.AUTO, fareEstimate = 30_000, paymentMethod = PaymentMethod.CASH,
    ).apply { id = 12 }

    private val driver = AuthUser(7, "driver-uid", Role.DRIVER, "Tài", null, null, "ACTIVE")
    private val otherDriver = AuthUser(99, "other-uid", Role.DRIVER, "Khác", null, null, "ACTIVE")

    @Test
    fun `customer reviews use the ride customer and reject a driver who was not assigned`() {
        val rides = mock(RideRepository::class.java)
        val jdbc = mock(JdbcTemplate::class.java)
        `when`(rides.findById(12)).thenReturn(Optional.of(ride()))

        val sqlSeen = mutableListOf<String>()
        `when`(jdbc.query(anyString(), any<RowMapper<Any>>(), any())).thenAnswer { inv ->
            val sql = inv.getArgument<String>(0)
            sqlSeen += sql
            val mapper = inv.getArgument<RowMapper<Any>>(1)
            if (sql.contains("COUNT(*)")) {
                val row = mock(ResultSet::class.java)
                `when`(row.getInt("stars")).thenReturn(5)
                `when`(row.getLong("total")).thenReturn(6L)
                listOf(mapper.mapRow(row, 0))
            } else {
                emptyList()
            }
        }

        val service = RideRatingService(rides, jdbc, mock(NotificationPort::class.java))
        val result = service.customerReviewsForRide(driver, 12)

        assertEquals(6L, result.total)
        assertEquals(5.0, result.averageRating)
        assertEquals(6L, result.distribution[5])
        assertEquals(emptyList<Any>(), result.recent)
        assertEquals(2, sqlSeen.size)
        assertEquals(2, sqlSeen.count { it.contains("DRIVER_TO_CUSTOMER") && it.contains("is_hidden = false") })

        assertThrows(NotFoundException::class.java) {
            service.customerReviewsForRide(otherDriver, 12)
        }
    }
}
