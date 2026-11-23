package com.nhom15.datxebackend.admin

import com.nhom15.datxebackend.admin.service.AdminRatingService
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate

class AdminRatingPaginationTests {
    private val admin = AuthUser(1, "admin-uid", Role.ADMIN, "Admin", null, null, "ACTIVE")

    private class JdbcHarness {
        val calls = mutableListOf<Pair<String, List<Any?>>>()

        val jdbc: JdbcTemplate = mock(JdbcTemplate::class.java) { inv ->
            // Mockito có thể giữ varargs thành 1 mảng hoặc tách riêng; chuẩn hóa cả hai.
            val args = inv.arguments.flatMap { arg ->
                if (arg is Array<*>) arg.toList() else listOf(arg)
            }
            calls += inv.method.name to args
            when (inv.method.name) {
                "queryForObject" -> 2L
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
    fun `list rejects overflowing page before touching the database`() {
        val jdbc = JdbcHarness()
        val service = AdminRatingService(jdbc.jdbc)

        val ex = assertThrows(BadRequestException::class.java) {
            service.list(admin, null, null, null, null, null, null, null, null, Int.MAX_VALUE, 100)
        }
        assertTrue(ex.message!!.contains("page"))
        assertTrue(ex.message!!.contains("1.000.000"))
        assertTrue(jdbc.calls.isEmpty())
    }

    @Test
    fun `list binds a non negative Long offset for a valid page`() {
        val jdbc = JdbcHarness()
        val service = AdminRatingService(jdbc.jdbc)

        service.list(admin, null, null, null, null, null, null, null, null, 3, 25)

        val args = jdbc.pagedQueryArgs()
        assertEquals(25, args[args.size - 2]) // LIMIT
        assertTrue(args.last() is Long) // OFFSET không được bind kiểu Int (dễ wrap âm)
        assertEquals(75L, args.last())
    }
}
