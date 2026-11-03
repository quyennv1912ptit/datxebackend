package com.nhom15.datxebackend.notification

import com.nhom15.datxebackend.notification.service.NotificationHistoryService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper

/**
 * Một bản ghi thông báo có `data` sai định dạng (thiếu messageId/payload) từng làm
 * hỏng cả API lịch sử thông báo. Các test dưới đây khoá lại hành vi chịu lỗi đó.
 */
class NotificationHistoryMappingTests {
    private val mapper = JsonMapper.builder().build()
    private val service = NotificationHistoryService(JdbcTemplate(), mapper)
    private val now: Instant = Instant.parse("2026-11-02T10:00:00Z")

    @Test
    fun `data thieu messageId va payload khong lam nem loi`() {
        val item = service.toItem(
            id = 20,
            type = "PROMO_TEST",
            rawData = """{"message":"du lieu thieu messageId va payload"}""",
            title = "Khuyen mai thu",
            body = "Giam 20% cho chuyen tiep theo",
            createdAt = now,
            readAt = null,
        )

        assertEquals("20", item.messageId)
        assertEquals("PROMO_TEST", item.type)
        assertTrue(item.payload.isObject)
        assertEquals("Khuyen mai thu", item.payload.path("title").stringValue())
        assertEquals("Giam 20% cho chuyen tiep theo", item.payload.path("body").stringValue())
    }

    @Test
    fun `data rong hoac null van tra ve item hop le`() {
        listOf(null, "", "   ").forEach { raw ->
            val item = service.toItem(
                id = 21,
                type = "RIDE_COMPLETED",
                rawData = raw,
                title = "Chuyen di hoan thanh",
                body = null,
                createdAt = now,
                readAt = now,
            )

            assertEquals("21", item.messageId)
            assertEquals("Chuyen di hoan thanh", item.payload.path("title").stringValue())
            assertEquals(now, item.readAt)
        }
    }

    @Test
    fun `json hop le giu nguyen messageId va payload`() {
        val raw = """
            {"type":"RIDE_NO_DRIVER","messageId":"04a59268-0725-48d2-893a-ef8e60af630f",
             "payload":{"title":"RIDE_NO_DRIVER","body":"RIDE_NO_DRIVER","rideId":8}}
        """.trimIndent()

        val item = service.toItem(
            id = 16,
            type = "RIDE_NO_DRIVER",
            rawData = raw,
            title = "ignored",
            body = "ignored",
            createdAt = now,
            readAt = null,
        )

        assertEquals("04a59268-0725-48d2-893a-ef8e60af630f", item.messageId)
        assertEquals(8, item.payload.path("rideId").intValue())
    }

    @Test
    fun `messageId khong phai string van khong lam nem loi`() {
        val item = service.toItem(
            id = 22,
            type = "PROMO",
            rawData = """{"messageId":12345,"payload":{}}""",
            title = null,
            body = null,
            createdAt = now,
            readAt = null,
        )

        assertEquals("12345", item.messageId)
        assertTrue(item.payload.isObject)
    }
}
