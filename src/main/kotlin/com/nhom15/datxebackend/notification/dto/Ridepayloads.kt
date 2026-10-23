package com.nhom15.datxebackend.notification.dto

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.websocket.PayloadType

data class RideUpdatedPayload(
    override val rideId: Long,
    val status: RideStatus
) : NotificationPayload {
    override val type: PayloadType get() = PayloadType.RIDE_UPDATED
}

data class HandoverConfirmedPayload(
    override val rideId: Long,
    val confirmedByCustomer: Boolean = true,
) : NotificationPayload {
    override val type: PayloadType get() = PayloadType.HANDOVER_CONFIRMED
}

data class RideCancelledPayload(
    override val rideId: Long,
    val reason: String?
) : NotificationPayload {
    override val type: PayloadType get() = PayloadType.RIDE_CANCELLED
}

data class RideCompletedPayload(
    override val rideId: Long,
    val finalFare: Long,
    val distanceKm: Double?
) : NotificationPayload {
    override val type: PayloadType get() = PayloadType.RIDE_COMPLETED
}

data class RideNoDriverPayload(
    override val rideId: Long,
    val status: RideStatus
) : NotificationPayload {
    override val type: PayloadType get() = PayloadType.RIDE_NO_DRIVER
}
