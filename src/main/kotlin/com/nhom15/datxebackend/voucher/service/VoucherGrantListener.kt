package com.nhom15.datxebackend.voucher.service

import com.nhom15.datxebackend.ride.event.RideCompleted
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.VoucherGrantedPayload

/** Chay SAU khi giao dich goc da commit; loi cap voucher chi ghi log, khong anh huong chuyen di. */
@Component
class VoucherGrantListener(private val grantService: VoucherGrantService, private val notifications: NotificationPort) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onRideCompleted(event: RideCompleted) {
        try {
            if (grantService.grantMilestoneIfReached(event.customerId)) {
                log.info("Da tang voucher moc chuyen cho khach {}", event.customerId)
                notifications.send(Recipient.Customer(event.customerId), VoucherGrantedPayload("MILESTONE"))
            }
        } catch (exception: Exception) {
            log.error("Khong tang duoc voucher moc cho khach {}", event.customerId, exception)
        }
    }
}
