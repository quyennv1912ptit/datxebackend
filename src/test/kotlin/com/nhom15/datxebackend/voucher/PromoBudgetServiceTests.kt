package com.nhom15.datxebackend.voucher

import com.nhom15.datxebackend.voucher.service.PromoBudgetService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet

/** Unit tests cho bảng promo_budget: đảm bảo reserve bị trích và hoàn lại đúng một số tiền. */
class PromoBudgetServiceTests {
    private fun budgetRs(limit: Long = 100_000, reserved: Long = 20_000): ResultSet {
        val rs = mock(ResultSet::class.java)
        `when`(rs.getLong(1)).thenReturn(limit)
        `when`(rs.getLong(2)).thenReturn(0L) // held
        `when`(rs.getLong(3)).thenReturn(0L) // spent
        `when`(rs.getLong(4)).thenReturn(reserved)
        return rs
    }

    private fun holdRs(amount: Long, consumedReserve: Long): ResultSet {
        val rs = mock(ResultSet::class.java)
        `when`(rs.getLong(1)).thenReturn(amount)
        `when`(rs.getLong(2)).thenReturn(consumedReserve)
        `when`(rs.getLong(3)).thenReturn(501L) // voucher_id
        `when`(rs.getObject(4, Long::class.javaObjectType)).thenReturn(null) // non-campaign
        return rs
    }

    private fun stubUpdates(jdbc: JdbcTemplate) {
        `when`(jdbc.update(anyString())).thenReturn(1)
        `when`(jdbc.update(anyString(), any<Any>())).thenReturn(1)
        `when`(jdbc.update(anyString(), any<Any>(), any<Any>())).thenReturn(1)
        `when`(jdbc.update(anyString(), any<Any>(), any<Any>(), any<Any>())).thenReturn(1)
        `when`(jdbc.update(anyString(), any<Any>(), any<Any>(), any<Any>(), any<Any>())).thenReturn(1)
        `when`(jdbc.update(anyString(), any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>())).thenReturn(1)
        `when`(jdbc.update(anyString(), any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>())).thenReturn(1)
    }

    private fun mockJdbc(
        reserved: Long = 20_000,
        holdRow: ResultSet? = null,
        spendExists: Boolean = false,
        releaseExists: Boolean = false,
        voucherCanRestore: Boolean = true,
    ): JdbcTemplate {
        val jdbc = mock(JdbcTemplate::class.java)
        stubUpdates(jdbc)

        `when`(jdbc.queryForObject(anyString(), any<RowMapper<Any>>())).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val mapper = inv.getArgument<RowMapper<Any>>(1)
            mapper.mapRow(budgetRs(reserved = reserved), 0)
        }
        `when`(jdbc.queryForObject(contains("SELECT id FROM promo_budget"), eq(Long::class.java))).thenReturn(1L)
        `when`(jdbc.query(anyString(), any<RowMapper<Any>>())).thenReturn(mutableListOf())
        `when`(jdbc.query(anyString(), any<RowMapper<Any>>(), any<Any>())).thenAnswer { inv ->
            val sql = inv.getArgument<String>(0)
            @Suppress("UNCHECKED_CAST")
            val mapper = inv.getArgument<RowMapper<Any>>(1)
            if (sql.contains("action = 'HOLD'") && holdRow != null) {
                mutableListOf(mapper.mapRow(holdRow, 0))
            } else {
                // SELECT campaign_id FROM vouchers -> non-campaign
                mutableListOf<Any?>(null)
            }
        }
        `when`(jdbc.queryForObject(anyString(), eq(Boolean::class.java), any<Any>())).thenAnswer { inv ->
            val sql = inv.getArgument<String>(0)
            when {
                sql.contains("action = 'SPEND'") -> spendExists
                sql.contains("action = 'RELEASE'") -> releaseExists
                else -> voucherCanRestore
            }
        }
        return jdbc
    }

    @Test
    fun `hold with discount below forecast consumes exactly the reserved forecast`() {
        val jdbc = mockJdbc(reserved = 20_000)

        PromoBudgetService(jdbc).hold(9001L, 501L, amount = 10_000L, forecast = 20_000L)

        verify(jdbc).update(contains("issuance_reserved = issuance_reserved - ?"), eq(20_000L), eq(10_000L))
        verify(jdbc).update(
            contains("'HOLD'"),
            eq(9001L), eq(501L), org.mockito.ArgumentMatchers.isNull<Any>(),
            eq(10_000L), eq(20_000L), any<Any>(),
        )
    }

    @Test
    fun `hold with discount above forecast still consumes only the forecast`() {
        val jdbc = mockJdbc(reserved = 20_000)

        PromoBudgetService(jdbc).hold(9002L, 501L, amount = 30_000L, forecast = 20_000L)

        verify(jdbc).update(contains("issuance_reserved = issuance_reserved - ?"), eq(20_000L), eq(30_000L))
        verify(jdbc).update(
            contains("'HOLD'"),
            eq(9002L), eq(501L), org.mockito.ArgumentMatchers.isNull<Any>(),
            eq(30_000L), eq(20_000L), any<Any>(),
        )
    }

    @Test
    fun `hold with insufficient reserve clamps consumption to the reserve actually available`() {
        val jdbc = mockJdbc(reserved = 5_000)

        PromoBudgetService(jdbc).hold(9003L, 501L, amount = 30_000L, forecast = 20_000L)

        verify(jdbc).update(contains("issuance_reserved = issuance_reserved - ?"), eq(5_000L), eq(30_000L))
        verify(jdbc).update(
            contains("'HOLD'"),
            eq(9003L), eq(501L), org.mockito.ArgumentMatchers.isNull<Any>(),
            eq(30_000L), eq(5_000L), any<Any>(),
        )
    }

    @Test
    fun `legacy voucher with zero forecast consumes no reserve`() {
        val jdbc = mockJdbc(reserved = 20_000)

        PromoBudgetService(jdbc).hold(9004L, 501L, amount = 10_000L, forecast = 0L)

        verify(jdbc).update(contains("issuance_reserved = issuance_reserved - ?"), eq(0L), eq(10_000L))
        verify(jdbc).update(
            contains("'HOLD'"),
            eq(9004L), eq(501L), org.mockito.ArgumentMatchers.isNull<Any>(),
            eq(10_000L), eq(0L), any<Any>(),
        )
    }

    @Test
    fun `release restores consumed reserve instead of the actual discount`() {
        val jdbc = mockJdbc(holdRow = holdRs(amount = 10_000L, consumedReserve = 20_000L))

        PromoBudgetService(jdbc).release(9001L)

        verify(jdbc).update(contains("issuance_reserved = issuance_reserved + ?"), eq(10_000L), eq(20_000L))
        verify(jdbc).update(
            contains("'RELEASE'"),
            eq(9001L), eq(501L), org.mockito.ArgumentMatchers.isNull<Any>(),
            eq(10_000L), eq(20_000L), any<Any>(),
        )
    }

    @Test
    fun `release of expired personal voucher returns consumed reserve and marks it released`() {
        val jdbc = mockJdbc(
            holdRow = holdRs(amount = 10_000L, consumedReserve = 20_000L),
            voucherCanRestore = false,
        )

        PromoBudgetService(jdbc).release(9001L)

        // Dù voucher đã hết hạn, khoản thực trích lúc HOLD vẫn phải hoàn; nếu không,
        // V47 sweeper sẽ bỏ sót vì ledger còn dòng HOLD => reserve treo vĩnh viễn.
        verify(jdbc).update(contains("issuance_reserved = issuance_reserved + ?"), eq(10_000L), eq(20_000L))
        verify(jdbc).update(contains("reserve_released = TRUE"), eq(501L))
    }

    @Test
    fun `release after spend does not refund the hold again`() {
        val jdbc = mockJdbc(holdRow = holdRs(amount = 10_000L, consumedReserve = 20_000L), spendExists = true)

        PromoBudgetService(jdbc).release(9001L)

        verify(jdbc, never()).update(contains("issuance_reserved = issuance_reserved + ?"), any<Any>(), any<Any>())
        verify(jdbc, never()).update(contains("'RELEASE'"), any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>())
    }

    @Test
    fun `spend after release does not move held into spent again`() {
        val jdbc = mockJdbc(holdRow = holdRs(amount = 10_000L, consumedReserve = 20_000L), releaseExists = true)

        PromoBudgetService(jdbc).spend(9001L)

        verify(jdbc, never()).update(contains("spent_amount = spent_amount + ?"), any<Any>(), any<Any>())
        verify(jdbc, never()).update(contains("'SPEND'"), any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>())
    }

    @Test
    fun `expired personal reward reserve is released once with the exact forecast`() {
        val jdbc = mock(JdbcTemplate::class.java)
        stubUpdates(jdbc)
        var released = false
        val expiredVoucher = mock(ResultSet::class.java)
        `when`(expiredVoucher.getLong(1)).thenReturn(701L)
        `when`(expiredVoucher.getLong(2)).thenReturn(5_000L)
        `when`(jdbc.queryForObject(contains("SELECT id FROM promo_budget"), eq(Long::class.java))).thenReturn(1L)
        `when`(jdbc.query(contains("reserve_released = FALSE"), any<RowMapper<Any>>())).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val mapper = inv.getArgument<RowMapper<Any>>(1)
            if (released) mutableListOf<Any>() else mutableListOf(mapper.mapRow(expiredVoucher, 0))
        }
        `when`(jdbc.update(contains("UPDATE vouchers SET reserve_released = TRUE"), any<Any>())).thenAnswer {
            released = true
            1
        }

        val service = PromoBudgetService(jdbc)
        service.releaseExpiredPersonalRewardReserves()
        service.releaseExpiredPersonalRewardReserves()

        verify(jdbc, times(1)).update(contains("UPDATE vouchers SET reserve_released = TRUE"), eq(701L))
        verify(jdbc, times(1)).update(contains("issuance_reserved = GREATEST(issuance_reserved - ?"), eq(5_000L))
        verify(jdbc, times(1)).update(contains("'RELEASE'"), eq(701L), eq(5_000L))
    }

    @Test
    fun `reserveIssuance releases expired personal reserves before reserving new ones`() {
        val jdbc = mock(JdbcTemplate::class.java)
        stubUpdates(jdbc)
        val expiredVoucher = mock(ResultSet::class.java)
        `when`(expiredVoucher.getLong(1)).thenReturn(702L)
        `when`(expiredVoucher.getLong(2)).thenReturn(3_000L)
        `when`(jdbc.queryForObject(anyString(), any<RowMapper<Any>>())).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val mapper = inv.getArgument<RowMapper<Any>>(1)
            mapper.mapRow(budgetRs(reserved = 0L), 0)
        }
        `when`(jdbc.queryForObject(contains("SELECT id FROM promo_budget"), eq(Long::class.java))).thenReturn(1L)
        `when`(jdbc.query(contains("reserve_released = FALSE"), any<RowMapper<Any>>())).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val mapper = inv.getArgument<RowMapper<Any>>(1)
            mutableListOf<Any>(mapper.mapRow(expiredVoucher, 0))
        }

        assertTrue(PromoBudgetService(jdbc).reserveIssuance(1_000L))

        verify(jdbc).update(contains("issuance_reserved = GREATEST(issuance_reserved - ?"), eq(3_000L))
        verify(jdbc).update(contains("issuance_reserved = issuance_reserved + ?"), eq(1_000L))
    }

    @Test
    fun `setLimit logs the delta instead of the new absolute limit`() {
        val increased = mockJdbc(reserved = 20_000)
        PromoBudgetService(increased).setLimit(150_000L)
        verify(increased).update(contains("BUDGET_SET"), eq(50_000L), any<Any>())
        val increaseNote = capturedNote(increased)
        assertTrue(increaseNote.contains("+50000"), increaseNote)

        val decreased = mockJdbc(reserved = 20_000)
        PromoBudgetService(decreased).setLimit(80_000L)
        verify(decreased).update(contains("BUDGET_SET"), eq(20_000L), any<Any>())
        val decreaseNote = capturedNote(decreased)
        assertTrue(decreaseNote.contains("-20000"), decreaseNote)
        assertTrue(decreaseNote.contains("100000đ -> 80000đ"))
    }

    private fun capturedNote(jdbc: JdbcTemplate): String {
        val captor = org.mockito.ArgumentCaptor.forClass(String::class.java)
        verify(jdbc).update(contains("BUDGET_SET"), any<Any>(), captor.capture())
        return captor.value
    }
}
