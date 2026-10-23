package com.nhom15.datxebackend.payment.event

import com.nhom15.datxebackend.common.domain.Money
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.event.DomainEvent
import java.time.Instant
import java.util.UUID

/** Phát bởi payment khi thanh toán thành công. Booking-core và realtime nghe. */
data class RidePaymentCompleted(
    val rideId: Long,
    val paymentId: Long,
    val customerId: Long,
    val driverId: Long,
    val amount: Money,
    val method: PaymentMethod,
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
