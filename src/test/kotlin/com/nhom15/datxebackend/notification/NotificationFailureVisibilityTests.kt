package com.nhom15.datxebackend.notification

import com.google.api.client.json.gson.GsonFactory
import com.google.gson.JsonParser
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.common.websocket.WsEnvelope
import com.nhom15.datxebackend.notification.api.DeliveryResult
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.channel.FcmChannel
import com.nhom15.datxebackend.notification.channel.FcmMessageFactory
import com.nhom15.datxebackend.notification.channel.StompChannel
import com.nhom15.datxebackend.notification.dto.RatingReceivedNotificationPayload
import com.nhom15.datxebackend.notification.dto.RideUpdatedPayload
import com.nhom15.datxebackend.notification.dto.VoucherCampaignPayload
import com.nhom15.datxebackend.notification.dto.VoucherGrantedPayload
import com.nhom15.datxebackend.notification.service.NotificationDispatcher
import com.nhom15.datxebackend.notification.service.PaymentNotificationListener
import com.nhom15.datxebackend.notification.service.PaymentNotificationSender
import com.nhom15.datxebackend.notification.service.TicketNotificationListener
import com.nhom15.datxebackend.notification.service.TicketNotificationSender
import com.nhom15.datxebackend.notification.service.WithdrawalNotificationListener
import com.nhom15.datxebackend.notification.service.WithdrawalNotificationSender
import com.nhom15.datxebackend.payment.event.PaymentUpdated
import com.nhom15.datxebackend.payment.event.WithdrawalReviewed
import com.nhom15.datxebackend.payment.entity.WithdrawalStatus
import com.nhom15.datxebackend.support.ticket.TicketReplied
import com.nhom15.datxebackend.support.ticket.TicketStatus
import io.micrometer.core.instrument.Metrics
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.Executor

/** M16/M17: localized FCM copy và failure visibility mà không làm gãy after-commit flow. */
class NotificationFailureVisibilityTests {
    // Mỗi listener ghi counter với tag payloadType/recipientRole khác nhau. find(name).counter()
    // chỉ trả về counter ĐẦU TIÊN, nên payment/withdrawal sẽ thấy delta = 0 dù đã increment.
    private fun countOf(metric: String): Double =
        Metrics.globalRegistry.meters
            .filter { it.id.name == metric }
            .filterIsInstance<io.micrometer.core.instrument.Counter>()
            .sumOf { it.count() }

    private fun failingSender(): Answer<Any?> = Answer { throw IllegalStateException("fcm down") }

    @Test fun `failed history insert does not break dispatch and increments a labeled metric`() {
        // defaultAnswer thay vì any()/eq(): Kotlin non-null params biến matcher thành null.
        val jdbc = mock(JdbcTemplate::class.java, Answer { invocation ->
            if (invocation.method.name == "update") throw IllegalStateException("history db down")
            0
        })
        val availableChannel = Answer<Any?> { invocation ->
            when (invocation.method.name) {
                "isAvailable", "send" -> true
                else -> null
            }
        }
        val dispatcher = NotificationDispatcher(
            mock(StompChannel::class.java, availableChannel),
            mock(FcmChannel::class.java, availableChannel),
            jdbc,
            jacksonObjectMapper(),
            Executor(Runnable::run), // run the delivery tasks so the result is not a timeout
            20,
        )
        val before = countOf("notification.history.write.failed")

        val result = dispatcher.send(Recipient.Customer(9), RideUpdatedPayload(42, RideStatus.SEARCHING))

        assertEquals(DeliveryResult.SENT_WS, result)
        assertEquals(1.0, countOf("notification.history.write.failed") - before, 0.0001)
    }

    @Test fun `ticket listener keeps after-commit flow alive and records failure`() {
        val sender = mock(TicketNotificationSender::class.java, failingSender())
        val event = TicketReplied(42, "HT-42", 11, Role.CUSTOMER, TicketStatus.WAITING_FOR_USER, "Đã xử lý")
        val before = countOf("notification.delivery.failed")

        assertDoesNotThrow { TicketNotificationListener(sender).replied(event) }

        assertEquals(1.0, countOf("notification.delivery.failed") - before, 0.0001)
    }

    @Test fun `withdrawal listener keeps after-commit flow alive and records failure`() {
        val sender = mock(WithdrawalNotificationSender::class.java, failingSender())
        val event = WithdrawalReviewed(7, 11, 500_000, WithdrawalStatus.REJECTED, "Sai số tài khoản")
        val before = countOf("notification.delivery.failed")

        assertDoesNotThrow { WithdrawalNotificationListener(sender).reviewed(event) }

        assertEquals(1.0, countOf("notification.delivery.failed") - before, 0.0001)
    }

    @Test fun `voucher and rating pushes use the localized DTO copy on lock screen`() {
        val cases = listOf(
            PayloadType.VOUCHER_GRANTED to VoucherGrantedPayload("WELCOME"),
            PayloadType.VOUCHER_CAMPAIGN to VoucherCampaignPayload(3, "NEW2026", body = "Giảm 20% chuyến đầu"),
            PayloadType.RATING_RECEIVED to RatingReceivedNotificationPayload(12, 5, "Tài xế thân thiện"),
        )
        cases.forEach { (type, payload) ->
            val message = FcmMessageFactory.build(
                "token", Recipient.Customer(9), WsEnvelope(type, payload, "message-$type"), "{}", 1_800_000_000_000L,
            )
            val json = JsonParser.parseString(GsonFactory.getDefaultInstance().toString(message)).asJsonObject
            val notification = json["notification"].asJsonObject
            assertEquals(payload.title, notification["title"].asString, "title for $type")
            assertEquals(payload.body, notification["body"].asString, "body for $type")
        }
    }

    @Test fun `payment listener reports failures for both recipients without throwing`() {
        val sender = mock(PaymentNotificationSender::class.java, failingSender())
        val listener = PaymentNotificationListener(sender)
        val event = PaymentUpdated(
            42, 11, 12, PaymentMethod.CASH, PaymentMethod.WALLET, "PAID", 100_000,
        )
        val before = countOf("notification.delivery.failed")

        assertDoesNotThrow { listener.updated(event) }

        assertEquals(2.0, countOf("notification.delivery.failed") - before, 0.0001)
    }
}
