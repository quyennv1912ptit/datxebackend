package com.nhom15.datxebackend.voucher.service

import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.notification.api.DeliveryResult
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.VoucherCampaignPayload
import com.nhom15.datxebackend.notification.service.NotificationFailureMetrics
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

data class VoucherCampaignPublished(
    val id: Long,
    val code: String,
    val campaignTitle: String?,
    val discountSummary: String,
    val claimLimit: Int,
    val description: String,
)

@Component
class VoucherCampaignPublishedListener(
    private val jdbc: JdbcTemplate,
    private val notifications: NotificationPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onCampaignPublished(event: VoucherCampaignPublished) {
        // V1__init.sql tạo bảng "customers", không có bảng/view "users".
        // Trạng thái hợp lệ: ACTIVE / INACTIVE / BANNED (customers_status_check).
        val customerIds = jdbc.queryForList(
            "SELECT id FROM customers WHERE status = 'ACTIVE'",
            Long::class.java,
        )
        val fullBody = buildString {
            append(event.discountSummary)
            append(" · Giới hạn ${event.claimLimit} suất.")
            if (event.description.isNotBlank()) {
                append(" ")
                append(event.description.trim())
            }
        }
        customerIds.filterNotNull().forEach { customerId ->
            try {
                val result = notifications.send(
                    Recipient.Customer(customerId),
                    VoucherCampaignPayload(
                        campaignId = event.id,
                        code = event.code,
                        campaignTitle = event.campaignTitle,
                        discountSummary = event.discountSummary,
                        claimLimit = event.claimLimit,
                        body = fullBody,
                    ),
                )
                if (result == DeliveryResult.UNREACHABLE) {
                    NotificationFailureMetrics.record(
                        "notification.delivery.unreachable", PayloadType.VOUCHER_CAMPAIGN.name, "CUSTOMER",
                    )
                }
            } catch (ex: Exception) {
                log.warn("Could not notify customer {} about voucher campaign {}", customerId, event.id, ex)
                NotificationFailureMetrics.record(
                    "notification.delivery.failed", PayloadType.VOUCHER_CAMPAIGN.name, "CUSTOMER",
                )
            }
        }
    }
}