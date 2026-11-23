package com.nhom15.datxebackend.rating

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.rating.service.RideRatingService
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate

/** `totalRatings` must describe the same population as `averageRating`, including hidden ratings. */
class RatingReceivedTotalsTests {
    @Test
    fun `received total includes hidden ratings while pagination uses visible rows`() {
        val jdbc = mock(JdbcTemplate::class.java) { invocation ->
            when (invocation.method.name) {
                "queryForObject" -> {
                    val sql = invocation.getArgument<String>(0)
                    when {
                        sql.contains("average_rating") -> 4.25
                        sql.contains("is_hidden") -> 5L // visible count
                        else -> 6L // all ratings
                    }
                }
                "query" -> emptyList<Any>()
                else -> null
            }
        }
        val service = RideRatingService(
            mock(RideRepository::class.java),
            jdbc,
            mock(NotificationPort::class.java),
        )
        val customer = AuthUser(11, "customer-uid", Role.CUSTOMER, "Khách", null, null, "ACTIVE")

        val result = service.received(customer, 0, 20)

        assertEquals(4.25, result.averageRating!!, 1e-9)
        assertEquals(6L, result.totalRatings)
        assertEquals(1, result.totalPages) // 5 visible rows / 20
        assertEquals(false, result.hasNext)
        assertEquals(emptyList<Any>(), result.ratings)
    }
}
