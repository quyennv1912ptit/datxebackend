package com.nhom15.datxebackend.notification.service

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.notification.api.DeliveryResult
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.TicketReplyPayload
import com.nhom15.datxebackend.support.ticket.TicketReplied
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class TicketNotificationListener(private val sender: TicketNotificationSender) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun replied(event: TicketReplied) {
        try {
            val result = sender.send(event)
            if (result == DeliveryResult.UNREACHABLE) {
                log.warn(
                    "NOTIFICATION_DELIVERY_UNREACHABLE messageId={} payloadType=TICKET_REPLY recipientRole={} recipientId={} ticketCode={}",
                    event.ticketCode, event.ownerRole, event.ownerId, event.ticketCode,
                )
                NotificationFailureMetrics.record("notification.delivery.unreachable", "TICKET_REPLY", event.ownerRole.name)
            }
        } catch (error: Exception) {
            log.error(
                "NOTIFICATION_DELIVERY_FAILED messageId={} payloadType=TICKET_REPLY recipientRole={} recipientId={} ticketCode={} error={}",
                event.ticketCode, event.ownerRole, event.ownerId, event.ticketCode, error.toString(), error,
            )
            NotificationFailureMetrics.record("notification.delivery.failed", "TICKET_REPLY", event.ownerRole.name)
        }
    }
}

@Component
class TicketNotificationSender(private val notifications: NotificationPort) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun send(event: TicketReplied): DeliveryResult {
        val recipient = if (event.ownerRole == Role.DRIVER) {
            Recipient.Driver(event.ownerId)
        } else {
            Recipient.Customer(event.ownerId)
        }
        return notifications.send(
            recipient,
            TicketReplyPayload(event.ticketId, event.ticketCode, event.status, event.message),
        )
    }
}
