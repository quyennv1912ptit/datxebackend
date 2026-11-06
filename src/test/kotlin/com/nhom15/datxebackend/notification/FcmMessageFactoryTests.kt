package com.nhom15.datxebackend.notification

import com.google.api.client.json.gson.GsonFactory
import com.google.gson.JsonParser
import com.nhom15.datxebackend.common.websocket.DeliveryPolicy
import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.common.websocket.WsEnvelope
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.channel.FcmMessageFactory
import com.nhom15.datxebackend.notification.dto.RideOfferPayload
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class FcmMessageFactoryTests {
    private val now = 1_800_000_000_000L
    private fun offer(deadline: Long): RideOfferPayload = Mockito.mock(RideOfferPayload::class.java).also {
        Mockito.`when`(it.expiresAt).thenReturn(deadline)
    }

    @Test
    fun `offer has visible notification data Android TTL channel sound and alert APNs expiry`() {
        val envelope = WsEnvelope(PayloadType.RIDE_OFFER, offer(now + 15_000), "offer-123")
        val message = FcmMessageFactory.build("device-token", Recipient.Driver(7), envelope, "wire-envelope", now)
        assertNotNull(message)
        val json = JsonParser.parseString(GsonFactory.getDefaultInstance().toString(message)).asJsonObject
        assertEquals("Có yêu cầu chuyến mới", json["notification"].asJsonObject["title"].asString)
        val data = json["data"].asJsonObject
        assertEquals("wire-envelope", data["payload"].asString)
        assertEquals("DRIVER", data["recipientRole"].asString)
        assertEquals("7", data["recipientId"].asString)
        val android = json["android"].asJsonObject
        assertEquals("15s", android["ttl"].asString)
        assertEquals("datxe-offers-v1", android["notification"].asJsonObject["channel_id"].asString)
        assertEquals("default", android["notification"].asJsonObject["sound"].asString)
        assertTrue(android["notification"].asJsonObject["default_vibrate_timings"].asBoolean)
        val apns = json["apns"].asJsonObject
        assertEquals("alert", apns["headers"].asJsonObject["apns-push-type"].asString)
        assertEquals("10", apns["headers"].asJsonObject["apns-priority"].asString)
        assertEquals(((now + 15_000) / 1000).toString(), apns["headers"].asJsonObject["apns-expiration"].asString)
        assertEquals("default", apns["payload"].asJsonObject["aps"].asJsonObject["sound"].asString)
    }

    @Test
    fun `expired or malformed offer is not sent`() {
        assertNull(FcmMessageFactory.build("token", Recipient.Driver(7), WsEnvelope(PayloadType.RIDE_OFFER, offer(now)), "{}", now))
        assertNull(FcmMessageFactory.build("token", Recipient.Driver(7), WsEnvelope(PayloadType.RIDE_OFFER, mapOf("rideId" to 12)), "{}", now))
    }

    @Test
    fun `offer close pushes have a meaningful title and are pushed when the socket is absent`() {
        val expired = FcmMessageFactory.build("token", Recipient.Driver(7), WsEnvelope(PayloadType.OFFER_EXPIRED, mapOf("rideId" to 12)), "{}", now)
        val cancelled = FcmMessageFactory.build("token", Recipient.Driver(7), WsEnvelope(PayloadType.OFFER_CANCELLED, mapOf("rideId" to 12)), "{}", now)
        val expiredJson = JsonParser.parseString(GsonFactory.getDefaultInstance().toString(expired)).asJsonObject
        val cancelledJson = JsonParser.parseString(GsonFactory.getDefaultInstance().toString(cancelled)).asJsonObject
        assertEquals("Lời mời nhận chuyến đã hết hạn", expiredJson["notification"].asJsonObject["title"].asString)
        assertEquals("Lời mời nhận chuyến đã hủy", cancelledJson["notification"].asJsonObject["title"].asString)
        assertEquals(DeliveryPolicy.PUSH_ALSO, PayloadType.OFFER_EXPIRED.policy)
        assertEquals(DeliveryPolicy.PUSH_ALSO, PayloadType.OFFER_CANCELLED.policy)
    }

    @Test
    fun `chat uses generic lock screen text and visible events are pushed despite a socket`() {
        val message = FcmMessageFactory.build("token", Recipient.Customer(9), WsEnvelope(PayloadType.CHAT_MESSAGE, mapOf("content" to "private address")), "{}", now)
        val json = JsonParser.parseString(GsonFactory.getDefaultInstance().toString(message)).asJsonObject
        assertFalse(json["notification"].toString().contains("private address"))
        assertEquals("datxe-updates-v1", json["android"].asJsonObject["notification"].asJsonObject["channel_id"].asString)
        assertEquals(DeliveryPolicy.PUSH_ALSO, PayloadType.RIDE_OFFER.policy)
        assertEquals(DeliveryPolicy.PUSH_ALSO, PayloadType.CHAT_MESSAGE.policy)
        assertEquals(DeliveryPolicy.WS_ONLY, PayloadType.CHAT_READ.policy)
    }
}
