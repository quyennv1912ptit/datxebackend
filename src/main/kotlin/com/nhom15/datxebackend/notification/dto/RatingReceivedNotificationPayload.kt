package com.nhom15.datxebackend.notification.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.nhom15.datxebackend.common.websocket.PayloadType

data class RatingReceivedNotificationPayload(
    val stars: Int,
    val comment: String? = null,
    val tags: List<String> = emptyList(),
) : NotificationPayload {
    @get:JsonIgnore
    override val rideId: Long? get() = null

    @get:JsonIgnore
    override val type: PayloadType = PayloadType.RATING_RECEIVED

    override val title: String
        get() = "Bạn nhận được đánh giá mới ($stars/5 sao)"

    override val body: String
        get() {
            val cleanComment = comment?.trim()?.takeIf { it.isNotEmpty() }
            return if (cleanComment != null) {
                "Đánh giá ẩn danh $stars sao: \"$cleanComment\""
            } else {
                "Bạn vừa nhận được đánh giá ẩn danh $stars/5 sao cho chuyến đi."
            }
        }
}
