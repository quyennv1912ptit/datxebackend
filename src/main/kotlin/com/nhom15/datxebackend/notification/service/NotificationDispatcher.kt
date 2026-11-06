package com.nhom15.datxebackend.notification.service

import com.nhom15.datxebackend.notification.dto.RideOfferPayload
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import com.nhom15.datxebackend.common.websocket.WsEnvelope
import com.nhom15.datxebackend.common.websocket.DeliveryPolicy.*
import com.nhom15.datxebackend.notification.api.DeliveryResult
import com.nhom15.datxebackend.notification.api.DeliveryResult.*
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.channel.FcmChannel
import com.nhom15.datxebackend.notification.channel.NotificationChannel
import com.nhom15.datxebackend.notification.channel.StompChannel
import com.nhom15.datxebackend.notification.dto.NotificationPayload
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class NotificationDispatcher(
    private val stomp: StompChannel,
    private val fcm: FcmChannel,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    @Qualifier("notificationExecutor")
    private val executor: Executor,
    @Value("\${app.notifications.delivery-timeout-ms:2000}")
    private val deliveryTimeoutMs: Long,
) : NotificationPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(to: Recipient, payload: NotificationPayload): DeliveryResult {

        val envelope = WsEnvelope(payload.type, payload)
        val result = when (payload.type.policy) {
            WS_ONLY ->
                if (trySend(stomp, to, envelope)) SENT_WS else UNREACHABLE

            WS_THEN_PUSH -> when {
                trySend(stomp, to, envelope) -> SENT_WS
                trySend(fcm, to, envelope) -> SENT_PUSH
                else -> UNREACHABLE
            }

            PUSH_ALSO -> {
                val wsTask = asyncSend(stomp, to, envelope)
                val pushTask = asyncSend(fcm, to, envelope)
                val ws = wsTask.join()
                val push = pushTask.join()
                when {
                    ws -> SENT_WS
                    push -> SENT_PUSH
                    else -> UNREACHABLE
                }
            }
        }
        persist(to, payload, envelope, result)
        log.info("notify type={} to={} policy={} result={}", payload.type, to, payload.type.policy, result)
        return result
    }

    private fun asyncSend(channel: NotificationChannel, to: Recipient, envelope: WsEnvelope<*>): CompletableFuture<Boolean> =
        try {
            CompletableFuture.supplyAsync({ trySend(channel, to, envelope) }, executor)
                .completeOnTimeout(false, deliveryTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            log.warn("Notification executor saturated for {}", to)
            CompletableFuture.completedFuture(false)
        }

    private fun trySend(channel: NotificationChannel, to: Recipient, env: WsEnvelope<*>): Boolean =
        try {
            val offer = env.payload as? RideOfferPayload
            (offer == null || offer.expiresAt > System.currentTimeMillis()) && channel.isAvailable(to) && channel.send(to, env)
        } catch (e: Exception) {
            log.warn("channel {} failed for {}: {}", channel::class.simpleName, to, e.message)
            false
        }

    private fun persist(
        to: Recipient,
        payload: NotificationPayload,
        envelope: WsEnvelope<*>,
        result: DeliveryResult,
    ) {
        try {
            val recipientColumn = when (to) {
                is Recipient.Customer -> "recipient_customer_id"
                is Recipient.Driver -> "recipient_driver_id"
            }
            val deliveryStatus = if (result == SENT_WS || result == SENT_PUSH) "SENT" else "FAILED"
            jdbcTemplate.update(
                """INSERT INTO notifications
                   ($recipientColumn, ride_id, notification_type, title, body, data, delivery_status, sent_at)
                   VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, CASE WHEN ? = 'SENT' THEN CURRENT_TIMESTAMP END)""".trimIndent(),
                to.id,
                payload.rideId,
                envelope.type.name,
                payload.title,
                payload.body,
                objectMapper.writeValueAsString(envelope),
                deliveryStatus,
                deliveryStatus,
            )
        } catch (e: Exception) {
            log.warn("Không thể lưu lịch sử thông báo cho {}: {}", to, e.message)
        }
    }
}
