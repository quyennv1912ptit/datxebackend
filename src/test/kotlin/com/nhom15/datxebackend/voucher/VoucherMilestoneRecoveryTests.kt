package com.nhom15.datxebackend.voucher

import com.nhom15.datxebackend.voucher.service.PromoBudgetService
import com.nhom15.datxebackend.voucher.service.VoucherGrantService
import com.nhom15.datxebackend.voucher.service.VoucherRewardPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate

/** Mốc bị bỏ sót ở chuyến 6/11/21 phải tự phục hồi ở chuyến sau mà không cấp trùng. */
class VoucherMilestoneRecoveryTests {
    private val customerId = 11L

    private fun mockGrantJdbc(completed: Long): JdbcTemplate {
        val jdbc = mock(JdbcTemplate::class.java)
        `when`(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Long::class.java), eq(customerId))).thenReturn(completed)
        `when`(jdbc.queryForObject(contains("FOR UPDATE"), eq(Long::class.java), eq(customerId))).thenReturn(customerId)
        `when`(jdbc.queryForObject(contains("SELECT EXISTS"), eq(Boolean::class.javaObjectType), eq(customerId), any<Any>()))
            .thenReturn(false)
        `when`(
            jdbc.queryForList(
                contains("INSERT INTO vouchers"), eq(Long::class.java),
                any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>(),
            ),
        ).thenReturn(listOf(501L))
        return jdbc
    }

    @Test
    fun `milestones are exposed from highest to lowest`() {
        assertEquals(listOf(20L, 10L, 5L), VoucherRewardPolicy.milestonesDesc())
    }

    @Test
    fun `six completed rides grant GO5 when GO5 was missed`() {
        val jdbc = mockGrantJdbc(completed = 6L)
        val promo = mock(PromoBudgetService::class.java)
        `when`(promo.reserveIssuance(5_000L)).thenReturn(true)

        assertTrue(VoucherGrantService(jdbc, promo).grantMilestoneIfReached(customerId))

        verify(jdbc, atLeastOnce()).queryForObject(
            contains("SELECT EXISTS"), eq(Boolean::class.javaObjectType), eq(customerId), eq(5L))
        verify(promo).reserveIssuance(5_000L)
        verify(jdbc).queryForList(
            contains("INSERT INTO vouchers"), eq(Long::class.java),
            eq("FIXED"), eq(10_000L), eq(10_000L), eq(0L), eq(30L), eq(customerId), eq(5), eq(5_000L),
        )
    }

    @Test
    fun `four completed rides grant no milestone`() {
        val jdbc = mockGrantJdbc(completed = 4L)

        assertEquals(false, VoucherGrantService(jdbc, mock(PromoBudgetService::class.java)).grantMilestoneIfReached(customerId))

        verify(jdbc, never()).queryForList(
            anyString(), eq(Long::class.java),
            any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>(), any<Any>(),
        )
    }
}
