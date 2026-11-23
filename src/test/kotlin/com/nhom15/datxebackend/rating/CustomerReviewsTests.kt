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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Optional

class CustomerReviewsTests {
    private fun ride() = Ride(
        customerId = 11, driverId = 7, vehicleId = 2, type = RideType.INSTANT,
        pickup = Location(), dropoff = Location(), vehicleType = VehicleType.CAR,
        transmission = Transmission.AUTO, fareEstimate = 30_000, paymentMethod = PaymentMethod.CASH,
    ).apply { id = 12 }

    private val driver = AuthUser(7, "driver-uid", Role.DRIVER, "Tài", null, null, "ACTIVE")
    private val otherDriver = AuthUser(99, "other-uid", Role.DRIVER, "Khác", null, null, "ACTIVE")

    private class JdbcHarness {
        val sqlSeen = mutableListOf<String>()
        var distribution: List<Pair<Int, Long>> = listOf(5 to 6L)
        var recent: List<ResultSet> = emptyList()

        val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java) { inv ->
            when (inv.method.name) {
                "query" -> {
                    val sql = inv.getArgument<String>(0)
                    sqlSeen += sql
                    @Suppress("UNCHECKED_CAST")
                    val mapper = inv.getArgument<RowMapper<Any>>(1)
                    if (sql.contains("COUNT(*)")) {
                        distribution.map { (stars, count) ->
                            mapper.mapRow(
                                row(longs = mapOf("stars" to stars.toLong(), "total" to count)), 0,
                            )
                        }
                    } else {
                        recent.map { mapper.mapRow(it, 0) }
                    }
                }
                else -> null
            }
        }
    }

    @Test
    fun `customer reviews use the ride customer and reject a driver who was not assigned`() {
        val rides = mock(RideRepository::class.java) { inv ->
            if (inv.method.name == "findById") Optional.of(ride()) else null
        }
        val jdbc = JdbcHarness()

        val service = RideRatingService(rides, jdbc.jdbc, mock(NotificationPort::class.java))
        val result = service.customerReviewsForRide(driver, 12)

        assertEquals(6L, result.total)
        assertEquals(5.0, result.averageRating)
        assertEquals(6L, result.distribution[5])
        assertEquals(emptyList<Any>(), result.recent)
        assertEquals(2, jdbc.sqlSeen.size)
        // Tổng/phân bố phải tính trên mọi đánh giá; chỉ danh sách recent được lọc ẩn.
        val aggregate = jdbc.sqlSeen.single { it.contains("COUNT(*)") }
        assertEquals(false, aggregate.contains("is_hidden"))
        assertEquals(1, jdbc.sqlSeen.count { it.contains("is_hidden = false") })

        assertThrows(NotFoundException::class.java) {
            service.customerReviewsForRide(otherDriver, 12)
        }
    }

    @Test
    fun `hiding a rating changes only the visible recent list, not the average`() {
        val rides = mock(RideRepository::class.java) { inv ->
            if (inv.method.name == "findById") Optional.of(ride()) else null
        }
        val jdbc = JdbcHarness()
        // Năm đánh giá 5 sao + một đánh giá 1 sao bị ẩn: 26/6 = 4.33 sao.
        jdbc.distribution = listOf(5 to 5L, 1 to 1L)
        jdbc.recent = listOf(
            row(
                longs = mapOf("id" to 101L, "stars" to 5L),
                strings = mapOf("comment" to "Tốt"),
                timestamp = Timestamp.from(Instant.now()),
            ),
        )
        val service = RideRatingService(rides, jdbc.jdbc, mock(NotificationPort::class.java))

        val visible = service.customerReviewsForRide(driver, 12)
        jdbc.recent = emptyList()
        val afterHiding = service.customerReviewsForRide(driver, 12)

        assertEquals(26.0 / 6, visible.averageRating!!, 1e-9)
        assertEquals(visible.averageRating, afterHiding.averageRating)
        assertEquals(6L, afterHiding.total)
        assertEquals(1L, afterHiding.distribution[1])
        assertEquals(1, visible.recent.size)
        assertEquals(0, afterHiding.recent.size)
    }

    @Test
    fun `driver reviews hide moderated content but keep the aggregate from all ratings`() {
        val jdbc = JdbcHarness()
        jdbc.distribution = listOf(5 to 5L, 1 to 1L)
        jdbc.recent = listOf(
            row(
                longs = mapOf("id" to 201L, "stars" to 5L),
                strings = mapOf("comment" to "Tốt"),
                timestamp = Timestamp.from(Instant.now()),
            ),
        )
        val service = RideRatingService(mock(RideRepository::class.java), jdbc.jdbc, mock(NotificationPort::class.java))

        val result = service.driverReviews(7)

        assertEquals(6L, result.total)
        assertEquals(26.0 / 6, result.averageRating!!, 1e-9)
        assertEquals(2, jdbc.sqlSeen.size)
        val aggregate = jdbc.sqlSeen.single { it.contains("COUNT(*)") }
        assertEquals(false, aggregate.contains("is_hidden"))
        val visible = jdbc.sqlSeen.single { it.contains("CUSTOMER_TO_DRIVER") && !it.contains("COUNT(*)") }
        assertTrue(visible.contains("is_hidden = false"))
    }
}

/** ResultSet giả lập không dùng Mockito matchers; top-level để gọi được trong SAM lambda. */
private fun row(
    singleLong: Long? = null,
    longs: Map<String, Long> = emptyMap(),
    strings: Map<String, String> = emptyMap(),
    timestamp: Timestamp? = null,
): ResultSet = mock(ResultSet::class.java) { inv ->
    when (inv.method.name) {
        "getInt" -> {
            val column = inv.arguments[0] as? String
            (column?.let(longs::get) ?: singleLong ?: 0L).toInt()
        }
        "getLong" -> {
            val column = inv.arguments[0]
            if (column is Number) singleLong ?: 0L else longs[column as String] ?: 0L
        }
        "getString" -> (inv.arguments[0] as? String)?.let(strings::get)
        "getTimestamp" -> timestamp
        else -> null
    }
}
