package com.nhom15.datxebackend.rating

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.rating.service.RideRatingService
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate

class RideRatingPaginationTests {
    private val customer = AuthUser(11, "customer-uid", Role.CUSTOMER, "Khách", null, null, "ACTIVE")

    private class JdbcHarness {
        val calls = mutableListOf<Pair<String, List<Any?>>>()

        val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java) { inv ->
            // Mockito có thể giữ varargs thành 1 mảng hoặc tách riêng; chuẩn hóa cả hai.
            val args = inv.arguments.flatMap { arg ->
                if (arg is Array<*>) arg.toList() else listOf(arg)
            }
            calls += inv.method.name to args
            when (inv.method.name) {
                "queryForObject" -> if (inv.getArgument<String>(0).contains("average_rating")) 4.5 else 5L
                "query" -> emptyList<Any>()
                else -> null
            }
        }

        fun pagedQueryArgs(): List<Any?> = calls
            .single { (method, args) ->
                method == "query" && (args.firstOrNull() as? String)?.contains("LIMIT ? OFFSET ?") == true
            }.second
    }

    @Test
    fun `received rejects overflowing page before touching the database`() {
        val jdbc = JdbcHarness()
        val service = RideRatingService(
            mock(RideRepository::class.java), jdbc.jdbc, mock(NotificationPort::class.java),
        )

        val ex = assertThrows(BadRequestException::class.java) {
            service.received(customer, Int.MAX_VALUE, 100)
        }
        assertTrue(ex.message!!.contains("page"))
        assertTrue(ex.message!!.contains("1.000.000"))
        assertTrue(jdbc.calls.isEmpty())
    }

    @Test
    fun `received binds a non negative Long offset for a valid page`() {
        val jdbc = JdbcHarness()
        val service = RideRatingService(
            mock(RideRepository::class.java), jdbc.jdbc, mock(NotificationPort::class.java),
        )

        service.received(customer, 2, 20)

        val args = jdbc.pagedQueryArgs()
        assertEquals(20, args[args.size - 2]) // LIMIT
        assertTrue(args.last() is Long) // OFFSET không được bind kiểu Int (dễ wrap âm)
        assertEquals(40L, args.last())
    }
}
