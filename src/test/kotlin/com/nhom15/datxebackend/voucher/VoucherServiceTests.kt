package com.nhom15.datxebackend.voucher

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.voucher.service.VoucherService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

class VoucherServiceTests {
    private val customer = AuthUser(42L, "cust-42", Role.CUSTOMER, "Khách", null, null, "ACTIVE")

    private fun mockCampaignVoucherRow(
        id: Long = 101L,
        code: String? = null,
        campaignCode: String? = "HE2026",
        campaignTitle: String? = "Ưu đãi mùa hè",
        description: String? = "Giảm giá hè",
        milestone: Int = 0,
        claimSequence: Int? = 3,
        campaignClaimLimit: Int? = 50,
        source: String = "SYSTEM_REWARD",
    ): ResultSet {
        val rs = mock(ResultSet::class.java)
        val now = Timestamp.from(Instant.now())
        val later = Timestamp.from(Instant.now().plusSeconds(86400))
        `when`(rs.getLong("id")).thenReturn(id)
        `when`(rs.getString("code")).thenReturn(code)
        `when`(rs.getString("campaign_code")).thenReturn(campaignCode)
        `when`(rs.getString("campaign_title")).thenReturn(campaignTitle)
        `when`(rs.getString("description")).thenReturn(description)
        `when`(rs.getObject("milestone", Int::class.javaObjectType)).thenReturn(milestone)
        `when`(rs.getObject("claim_sequence", Int::class.javaObjectType)).thenReturn(claimSequence)
        `when`(rs.getObject("campaign_claim_limit", Int::class.javaObjectType)).thenReturn(campaignClaimLimit)
        `when`(rs.getString("discount_type")).thenReturn("FIXED")
        `when`(rs.getLong("value")).thenReturn(20_000L)
        `when`(rs.getObject("max_discount", Long::class.javaObjectType)).thenReturn(20_000L)
        `when`(rs.getLong("minimum_order_amount")).thenReturn(50_000L)
        `when`(rs.getInt("usage_count")).thenReturn(0)
        `when`(rs.getObject("usage_limit", Int::class.javaObjectType)).thenReturn(1)
        `when`(rs.getTimestamp("valid_from")).thenReturn(now)
        `when`(rs.getTimestamp("valid_to")).thenReturn(later)
        `when`(rs.getObject("owner_customer_id", Long::class.javaObjectType)).thenReturn(customer.id)
        `when`(rs.getString("source")).thenReturn(source)
        `when`(rs.getString("allowed_payment_method")).thenReturn(null)
        return rs
    }

    @Test
    fun `listAvailable preserves campaign metadata and milestone instead of defaulting to welcome`() {
        val jdbc = mock(JdbcTemplate::class.java)
        val sqlSeen = mutableListOf<String>()
        `when`(jdbc.query(anyString(), any<RowMapper<Any>>(), any(), any())).thenAnswer { inv ->
            val sql = inv.getArgument<String>(0)
            sqlSeen += sql
            @Suppress("UNCHECKED_CAST")
            val mapper = inv.getArgument<RowMapper<Any>>(1)
            listOf(
                mapper.mapRow(mockCampaignVoucherRow(), 0),
                mapper.mapRow(
                    mockCampaignVoucherRow(
                        id = 102L,
                        campaignCode = null,
                        campaignTitle = null,
                        description = null,
                        milestone = 5,
                        claimSequence = null,
                        campaignClaimLimit = null,
                    ),
                    1,
                ),
            )
        }

        val service = VoucherService(jdbc)
        val results = service.listAvailable(customer)

        assertEquals(2, results.size)
        assertTrue(sqlSeen.single().contains("LEFT JOIN vouchers c ON c.id = v.campaign_id"))
        assertEquals("HE2026", results[0].campaignCode)
        assertEquals("Ưu đãi mùa hè", results[0].campaignTitle)
        assertEquals("Giảm giá hè", results[0].description)
        assertEquals(3, results[0].claimSequence)
        assertEquals(50, results[0].campaignClaimLimit)
        assertEquals(5, results[1].milestone)
    }

    @Test
    fun `claimCampaign returns full campaign title, code, sequence and copies campaign_title`() {
        val jdbc = mock(JdbcTemplate::class.java)
        val sqlSeen = mutableListOf<String>()

        `when`(jdbc.query(anyString(), any<RowMapper<Any>>(), anyLong())).thenAnswer { inv ->
            sqlSeen += inv.getArgument<String>(0)
            listOf(arrayOf<Any?>("FIXED", 20_000L, 20_000L, 50_000L, 50, Instant.now(), Instant.now().plusSeconds(3600), null, 10_000L))
        }
        `when`(jdbc.query(anyString(), any<RowMapper<Any>>(), anyLong(), anyLong(), anyLong(), anyLong())).thenAnswer { inv ->
            sqlSeen += inv.getArgument<String>(0)
            listOf(7)
        }
        `when`(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long::class.java), any(), any(), any())).thenAnswer { inv ->
            sqlSeen += inv.getArgument<String>(0)
            555L
        }
        `when`(jdbc.queryForObject(anyString(), any<RowMapper<Any>>(), org.mockito.ArgumentMatchers.eq(555L))).thenAnswer { inv ->
            sqlSeen += inv.getArgument<String>(0)
            @Suppress("UNCHECKED_CAST")
            val mapper = inv.getArgument<RowMapper<Any>>(1)
            mapper.mapRow(mockCampaignVoucherRow(id = 555L, claimSequence = 7), 0)
        }

        val service = VoucherService(jdbc)
        val claimed = service.claimCampaign(customer, 99L)

        assertEquals(555L, claimed.id)
        assertEquals("HE2026", claimed.campaignCode)
        assertEquals("Ưu đãi mùa hè", claimed.campaignTitle)
        assertEquals(7, claimed.claimSequence)
        assertEquals(50, claimed.campaignClaimLimit)
        assertTrue(sqlSeen.any { it.contains("campaign_title") && it.contains("INSERT INTO vouchers") })
        assertTrue(sqlSeen.last().contains("LEFT JOIN vouchers c ON c.id = v.campaign_id"))
    }
}
