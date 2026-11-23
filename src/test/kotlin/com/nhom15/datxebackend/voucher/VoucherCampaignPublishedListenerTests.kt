package com.nhom15.datxebackend.voucher

import com.nhom15.datxebackend.notification.api.DeliveryResult
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.NotificationPayload
import com.nhom15.datxebackend.notification.dto.VoucherCampaignPayload
import com.nhom15.datxebackend.voucher.service.VoucherCampaignPublished
import com.nhom15.datxebackend.voucher.service.VoucherCampaignPublishedListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate

class VoucherCampaignPublishedListenerTests {
    private val event = VoucherCampaignPublished(
        id = 7L,
        code = "HE2026",
        campaignTitle = "Ưu đãi hè",
        discountSummary = "Giảm 20k",
        claimLimit = 50,
        description = "Áp dụng cho khách mới",
    )

    /**
     * NotificationPort là interface Kotlin với tham số non-null: Mockito any()/capture() trả null
     * và bị Kotlin null-check chặn. Dùng default answer để ghi nhận lời gọi thay vì captor.
     */
    private fun recordingNotifications(
        sent: MutableList<Pair<Recipient, NotificationPayload>>,
        failForCustomerId: Long? = null,
    ): NotificationPort = mock(NotificationPort::class.java) { inv ->
        if (inv.method.name == "send") {
            val recipient = inv.arguments[0] as Recipient
            val payload = inv.arguments[1] as NotificationPayload
            sent += recipient to payload
            if ((recipient as? Recipient.Customer)?.id == failForCustomerId) {
                throw IllegalStateException("FCM down")
            }
            DeliveryResult.SENT_WS
        } else {
            org.mockito.Mockito.RETURNS_DEFAULTS.answer(inv)
        }
    }

    @Test
    fun `published campaign queries customers table and notifies active customers`() {
        val jdbc = mock(JdbcTemplate::class.java)
        val sqlSeen = mutableListOf<String>()
        `when`(jdbc.queryForList(anyString(), eq(Long::class.java))).thenAnswer { inv ->
            sqlSeen += inv.getArgument<String>(0)
            mutableListOf<Long?>(11L, 12L)
        }
        val sent = mutableListOf<Pair<Recipient, NotificationPayload>>()

        VoucherCampaignPublishedListener(jdbc, recordingNotifications(sent)).onCampaignPublished(event)

        assertEquals(1, sqlSeen.size)
        assertTrue(sqlSeen.single().contains("FROM customers"), sqlSeen.single())
        assertTrue(sqlSeen.single().contains("status = 'ACTIVE'"), sqlSeen.single())
        assertFalse(sqlSeen.single().contains("users"), sqlSeen.single())

        assertEquals(2, sent.size)
        assertEquals(listOf(11L, 12L), sent.map { (it.first as Recipient.Customer).id })
        sent.forEach { (_, payload) ->
            assertTrue(payload is VoucherCampaignPayload)
            val campaignPayload = payload as VoucherCampaignPayload
            assertEquals(7L, campaignPayload.campaignId)
            assertEquals("HE2026", campaignPayload.code)
            assertEquals("Ưu đãi hè", campaignPayload.campaignTitle)
            assertTrue(campaignPayload.body.contains("Giảm 20k"))
            assertTrue(campaignPayload.body.contains("Áp dụng cho khách mới"))
        }
    }

    @Test
    fun `notification failure for one customer does not block the others`() {
        val jdbc = mock(JdbcTemplate::class.java)
        `when`(jdbc.queryForList(anyString(), eq(Long::class.java))).thenReturn(mutableListOf<Long?>(11L, 12L))
        val sent = mutableListOf<Pair<Recipient, NotificationPayload>>()

        VoucherCampaignPublishedListener(jdbc, recordingNotifications(sent, failForCustomerId = 11L))
            .onCampaignPublished(event)

        assertEquals(listOf(11L, 12L), sent.map { (it.first as Recipient.Customer).id })
    }
}
