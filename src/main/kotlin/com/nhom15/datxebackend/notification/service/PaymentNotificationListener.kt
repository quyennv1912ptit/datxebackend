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
            try { sender.send(event, recipient) }
            catch (error: Exception) { log.error("Cannot notify payment change for ride {} to {}", event.rideId, recipient.key, error) }
        }
    }
}
@Component
class PaymentNotificationSender(private val notifications: NotificationPort) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun send(event: PaymentUpdated, recipient: Recipient): DeliveryResult = notifications.send(recipient,
        PaymentNotificationPayload(event.rideId, event.fromMethod, event.method, event.status, event.amount))
}
