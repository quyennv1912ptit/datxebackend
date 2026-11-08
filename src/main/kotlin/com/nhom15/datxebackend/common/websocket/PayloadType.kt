package com.nhom15.datxebackend.common.websocket

enum class PayloadType(val policy: DeliveryPolicy) {
    // A subscribed socket does not prove the mobile app is foreground/visible.
    RIDE_OFFER(DeliveryPolicy.PUSH_ALSO),
    // A backgrounded driver has no live socket; the closure must still reach the phone.
    OFFER_EXPIRED(DeliveryPolicy.PUSH_ALSO),
    OFFER_CANCELLED(DeliveryPolicy.PUSH_ALSO),
    RIDE_UPDATED(DeliveryPolicy.PUSH_ALSO),
    HANDOVER_CONFIRMED(DeliveryPolicy.PUSH_ALSO),
    RIDE_CANCELLED(DeliveryPolicy.PUSH_ALSO),
    RIDE_COMPLETED(DeliveryPolicy.PUSH_ALSO),
    RIDE_NO_DRIVER(DeliveryPolicy.PUSH_ALSO),
    RIDE_ASSIGNED(DeliveryPolicy.PUSH_ALSO),
    DRIVER_DOCUMENT_REVIEWED(DeliveryPolicy.PUSH_ALSO),
    DRIVER_VERIFICATION_UPDATED(DeliveryPolicy.PUSH_ALSO),
    DRIVER_FORCE_OFFLINE(DeliveryPolicy.PUSH_ALSO),
    PAYMENT_UPDATED(DeliveryPolicy.PUSH_ALSO),
    WITHDRAWAL_REVIEWED(DeliveryPolicy.PUSH_ALSO),
    CHAT_MESSAGE(DeliveryPolicy.PUSH_ALSO),
    CHAT_READ(DeliveryPolicy.WS_ONLY),
    TICKET_REPLY(DeliveryPolicy.PUSH_ONLY),
}
