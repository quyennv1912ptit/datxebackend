package com.nhom15.datxebackend.notification.channel

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.common.websocket.WsEnvelope
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.NotificationPayload
import com.nhom15.datxebackend.notification.dto.RideOfferPayload

/** Notification+data lets Android/APNs display alerts without waiting for background JS. */
object FcmMessageFactory {
    fun build(token: String, to: Recipient, envelope: WsEnvelope<*>, payload: String, now: Long = System.currentTimeMillis()): Message? {
        val offer = envelope.type == PayloadType.RIDE_OFFER
        val deadline = if (offer) (envelope.payload as? RideOfferPayload)?.expiresAt ?: return null else now + 3_600_000
        val ttl = deadline - now
        if (ttl <= 0) return null
        val channel = if (offer) "datxe-offers-v1" else "datxe-updates-v1"
        // Payload có localized title/body (rút tiền, vé, đánh giá, ticket...) thì dùng nguyên chuỗi đó
        // trên lock-screen. Payload chỉ có default type.name thì giữ copy generic tiếng Việt.
        val localized = envelope.payload as? NotificationPayload
        // Payload có thể là mock/không khai báo type: vẫn ưu tiên localized copy thật, nhưng không NPE.
        val localizedTitle = localized?.let { p -> p.type?.name?.let { name -> p.title.takeIf { it != name } } }
        val localizedBody = localized?.let { p -> p.type?.name?.let { name -> p.body.takeIf { it != name } } }
        val title = when (envelope.type) {
            PayloadType.RIDE_OFFER -> "Có yêu cầu chuyến mới"
            PayloadType.CHAT_MESSAGE -> "Có tin nhắn mới"
            PayloadType.RIDE_ASSIGNED -> "Đã có tài xế nhận chuyến"
            PayloadType.RIDE_COMPLETED -> "Chuyến đi đã hoàn tất"
            PayloadType.RIDE_CANCELLED -> "Chuyến đi đã bị hủy"
            PayloadType.RIDE_NO_DRIVER -> "Chưa tìm được tài xế"
            PayloadType.OFFER_EXPIRED -> "Lời mời nhận chuyến đã hết hạn"
            PayloadType.OFFER_CANCELLED -> "Lời mời nhận chuyến đã hủy"
            PayloadType.HANDOVER_CONFIRMED -> "Biên bản đã được xác nhận"
            PayloadType.DRIVER_VERIFICATION_UPDATED -> "Hồ sơ tài xế có cập nhật"
            PayloadType.DRIVER_FORCE_OFFLINE -> "Đã tạm ngừng nhận chuyến"
            else -> localizedTitle ?: "Cập nhật chuyến đi"
        }
        val body = when {
            offer -> "Mở ứng dụng để xem và nhận chuyến trước khi hết hạn."
            envelope.type in VISIBLE_RESULT_TYPES -> localizedBody ?: if (envelope.type == PayloadType.WITHDRAWAL_REVIEWED)
                "Mở ứng dụng để xem kết quả rút tiền." else "Mở ứng dụng để xem kết quả."
            else -> localizedBody ?: "Mở ứng dụng để xem thông tin mới nhất."
        }
        val androidNotification = AndroidNotification.builder()
            .setChannelId(channel)
            .setTag(envelope.messageId)
            .setSound("default")
            .setDefaultVibrateTimings(true)
            .setPriority(AndroidNotification.Priority.HIGH)
            .setVisibility(AndroidNotification.Visibility.PRIVATE)
        return Message.builder()
            .setToken(token)
            .putData("payload", payload)
            .putData("recipientRole", to.role.name)
            .putData("recipientId", to.id.toString())
            .setNotification(Notification.builder().setTitle(title).setBody(body).build())
            .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH)
                .setTtl(ttl).setNotification(androidNotification.build()).build())
            .setApnsConfig(ApnsConfig.builder()
                .putHeader("apns-push-type", "alert")
                .putHeader("apns-priority", "10")
                .putHeader("apns-expiration", (deadline / 1000).toString())
                .putHeader("apns-collapse-id", envelope.messageId)
                .setAps(Aps.builder().setSound("default").setContentAvailable(true).build()).build())
            .build()
    }

    private val VISIBLE_RESULT_TYPES = setOf(
        PayloadType.WITHDRAWAL_REVIEWED,
        PayloadType.PAYMENT_UPDATED,
        PayloadType.TICKET_REPLY,
        PayloadType.VOUCHER_GRANTED,
        PayloadType.VOUCHER_CAMPAIGN,
        PayloadType.RATING_RECEIVED,
    )
}
