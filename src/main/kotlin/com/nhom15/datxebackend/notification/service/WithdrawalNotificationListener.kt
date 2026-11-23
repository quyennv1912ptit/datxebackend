package com.nhom15.datxebackend.notification.service

import com.nhom15.datxebackend.notification.api.*
import com.nhom15.datxebackend.notification.dto.WithdrawalNotificationPayload
import com.nhom15.datxebackend.payment.event.WithdrawalReviewed
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class WithdrawalNotificationListener(private val sender: WithdrawalNotificationSender) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun reviewed(event: WithdrawalReviewed) {
        // Notification history uses its own transaction. A delivery failure must never undo a payout/refund.
        try {
            val result = sender.send(event)
            if (result == DeliveryResult.UNREACHABLE) {
                log.warn(
                    "NOTIFICATION_DELIVERY_UNREACHABLE messageId={} payloadType=WITHDRAWAL_REVIEWED recipientRole=DRIVER recipientId={} requestId={}",
                    event.requestId, event.driverId, event.requestId,
                )
                NotificationFailureMetrics.record("notification.delivery.unreachable", "WITHDRAWAL_REVIEWED", "DRIVER")
            }
        } catch (error: Exception) {
            log.error(
                "NOTIFICATION_DELIVERY_FAILED messageId={} payloadType=WITHDRAWAL_REVIEWED recipientRole=DRIVER recipientId={} requestId={} error={}",
                event.requestId, event.driverId, event.requestId, error.toString(), error,
            )
            NotificationFailureMetrics.record("notification.delivery.failed", "WITHDRAWAL_REVIEWED", "DRIVER")
        }
    }
}

@Component
class WithdrawalNotificationSender(private val notifications: NotificationPort) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun send(event: WithdrawalReviewed): DeliveryResult = notifications.send(Recipient.Driver(event.driverId),
        WithdrawalNotificationPayload(event.requestId, event.amount, event.status, event.rejectReason))
}
