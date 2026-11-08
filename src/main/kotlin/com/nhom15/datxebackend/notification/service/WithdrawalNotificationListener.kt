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
            if (result == DeliveryResult.UNREACHABLE) log.warn("Withdrawal {} notification could not reach driver {}", event.requestId, event.driverId)
        } catch (error: Exception) {
            log.error("Cannot notify withdrawal {} to driver {}", event.requestId, event.driverId, error)
        }
    }
}

@Component
class WithdrawalNotificationSender(private val notifications: NotificationPort) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun send(event: WithdrawalReviewed): DeliveryResult = notifications.send(Recipient.Driver(event.driverId),
        WithdrawalNotificationPayload(event.requestId, event.amount, event.status, event.rejectReason))
}
