package com.nhom15.datxebackend.notification.dto

import com.nhom15.datxebackend.common.websocket.PayloadType

data class VoucherCampaignPayload(
    val campaignId: Long,
    val code: String,
    val campaignTitle: String? = null,
    val discountSummary: String? = null,
    val claimLimit: Int? = null,
    override val body: String,
) : NotificationPayload {
    override val type: PayloadType get() = PayloadType.VOUCHER_CAMPAIGN
    override val title: String
        get() = if (!campaignTitle.isNullOrBlank()) {
            "$campaignTitle ($code)"
        } else {
            "Ưu đãi mới: $code"
        }
}