package com.nhom15.datxebackend.common.websocket

enum class PayloadType(val policy: DeliveryPolicy) {
    RIDE_OFFER(DeliveryPolicy.WS_THEN_PUSH),
    OFFER_EXPIRED(DeliveryPolicy.WS_ONLY),
    OFFER_CANCELLED(DeliveryPolicy.WS_ONLY),
    RIDE_UPDATED(DeliveryPolicy.WS_THEN_PUSH),
    HANDOVER_CONFIRMED(DeliveryPolicy.WS_THEN_PUSH),
    RIDE_CANCELLED(DeliveryPolicy.WS_THEN_PUSH),
    RIDE_COMPLETED(DeliveryPolicy.PUSH_ALSO),
    RIDE_NO_DRIVER(DeliveryPolicy.WS_THEN_PUSH),
    RIDE_ASSIGNED(DeliveryPolicy.WS_THEN_PUSH),
    DRIVER_DOCUMENT_REVIEWED(DeliveryPolicy.WS_THEN_PUSH),
    DRIVER_VERIFICATION_UPDATED(DeliveryPolicy.WS_THEN_PUSH),
    DRIVER_FORCE_OFFLINE(DeliveryPolicy.WS_THEN_PUSH),
    CHAT_MESSAGE(DeliveryPolicy.WS_THEN_PUSH),
    CHAT_READ(DeliveryPolicy.WS_ONLY),
}
