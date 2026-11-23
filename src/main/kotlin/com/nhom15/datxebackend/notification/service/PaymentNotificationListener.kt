package com.nhom15.datxebackend.notification.service

import com.nhom15.datxebackend.notification.api.*
import com.nhom15.datxebackend.notification.dto.PaymentNotificationPayload
import com.nhom15.datxebackend.payment.event.PaymentUpdated
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.*
import org.springframework.transaction.event.*

@Component
class PaymentNotificationListener(private val sender: PaymentNotificationSender) {
    private val log = LoggerFactory.getLogger(javaClass)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun updated(event: PaymentUpdated) {
        for (recipient in listOf(Recipient.Driver(event.driverId), Recipient.Customer(event.customerId))) {
            try {
                val result = sender.send(event, recipient)
                if (result == DeliveryResult.UNREACHABLE) {
                    log.warn(
                        "NOTIFICATION_DELIVERY_UNREACHABLE payloadType=PAYMENT_UPDATED recipientRole={} recipientId={} rideId={}",
                        recipient.role.name, recipient.id, event.rideId,
                    )
                    NotificationFailureMetrics.record(
                        "notification.delivery.unreachable", "PAYMENT_UPDATED", recipient.role.name,
                    )
                }
            }
            catch (error: Exception) {
                log.error(
                    "NOTIFICATION_DELIVERY_FAILED messageId={} payloadType=PAYMENT_UPDATED recipientRole={} recipientId={} rideId={} error={}",
                    "payment-${event.rideId}", recipient.role.name, recipient.id, event.rideId, error.toString(), error,
                )
                NotificationFailureMetrics.record("notification.delivery.failed", "PAYMENT_UPDATED", recipient.role.name)
            }
        }
    }
}
@Component
class PaymentNotificationSender(private val notifications: NotificationPort) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun send(event: PaymentUpdated, recipient: Recipient): DeliveryResult = notifications.send(recipient,
        PaymentNotificationPayload(event.rideId, event.fromMethod, event.method, event.status, event.amount))
}
