package com.nhom15.datxebackend.voucher.service

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.voucher.dto.AdminVoucherCreatedResponse
import com.nhom15.datxebackend.voucher.dto.BudgetLedgerItemResponse
import com.nhom15.datxebackend.voucher.dto.CampaignClaimantResponse
import com.nhom15.datxebackend.voucher.dto.CreateAdminVoucherRequest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp

@Service
class AdminVoucherService(
    private val jdbcTemplate: JdbcTemplate,
    private val promoBudget: PromoBudgetService? = null,
    private val events: ApplicationEventPublisher? = null,
) {
            @Transactional(readOnly = true)
    fun list(actor: AuthUser): List<AdminVoucherCreatedResponse> {
        requireAdmin(actor)
        return jdbcTemplate.query(
            """SELECT v.id, v.code, v.campaign_title, v.source, v.active, v.owner_customer_id, v.discount_type, v.value, v.max_discount,
                      v.minimum_order_amount, v.usage_limit, v.valid_from, v.valid_to, v.allowed_payment_method,
                      v.description, v.campaign_claim_count, v.campaign_budget_limit, v.forecast_reserve_per_use, v.usage_count,
                      COALESCE(cb.held_amount,0) AS campaign_held_amount,
                      COALESCE(cb.spent_amount,0) AS campaign_spent_amount,
                      COALESCE(cb.released_amount,0) AS campaign_released_amount,
                      COALESCE((SELECT COUNT(*) FROM promo_budget_ledger l WHERE l.campaign_id=v.id AND l.action='HOLD'),0)
                        - COALESCE((SELECT COUNT(*) FROM promo_budget_ledger l WHERE l.campaign_id=v.id AND l.action IN ('SPEND','RELEASE') AND l.ride_id IS NOT NULL),0) AS campaign_held_count,
                      COALESCE((SELECT COUNT(*) FROM promo_budget_ledger l WHERE l.campaign_id=v.id AND l.action='RELEASE' AND l.ride_id IS NOT NULL),0) AS campaign_cancelled_count,
                      COALESCE((SELECT SUM(l.amount) FROM promo_budget_ledger l WHERE l.campaign_id=v.id AND l.action='RELEASE' AND l.ride_id IS NOT NULL),0) AS campaign_cancelled_amount,
                      COALESCE((SELECT COUNT(*) FROM vouchers child JOIN voucher_usages vu ON vu.voucher_id=child.id JOIN rides r ON r.id=vu.ride_id WHERE child.campaign_id=v.id AND r.status='COMPLETED'),0) AS campaign_used_count
               FROM vouchers v LEFT JOIN voucher_campaign_budget cb ON cb.campaign_id=v.id
               WHERE v.campaign_id IS NULL ORDER BY v.id DESC LIMIT 500""".trimIndent(),
        ) { rs, _ ->
            val source = rs.getString("source")
            val isActive = rs.getBoolean("active")
            val validTo = rs.getTimestamp("valid_to").toInstant()
            val usageCount = rs.getInt("usage_count")
            val usageLimit = rs.getObject("usage_limit", Int::class.javaObjectType)
            val forecastPerUse = rs.getLong("forecast_reserve_per_use")
            val systemReserveAmount = if (source != "PUBLIC_CODE" && isActive && validTo.isAfter(java.time.Instant.now())) {
                val remainingUses = ((usageLimit ?: 1) - usageCount).coerceAtLeast(0)
                forecastPerUse * remainingUses
            } else {
                0L
            }
            AdminVoucherCreatedResponse(
                id = rs.getLong("id"),
                code = rs.getString("code"), campaignTitle = rs.getString("campaign_title"),
                source = source,
                active = isActive,
                ownerCustomerId = rs.getObject("owner_customer_id", Long::class.javaObjectType),
                discountType = rs.getString("discount_type"),
                value = rs.getLong("value"),
                maxDiscount = rs.getObject("max_discount", Long::class.javaObjectType),
                minimumOrderAmount = rs.getLong("minimum_order_amount"),
                usageLimit = usageLimit,
                validFrom = rs.getTimestamp("valid_from").toInstant(),
                validTo = validTo,
                allowedPaymentMethod = rs.getString("allowed_payment_method"),
                description = rs.getString("description") ?: "",
                campaignClaimCount = rs.getInt("campaign_claim_count"),
                campaignUsedCount = rs.getInt("campaign_used_count"),
                campaignHeldCount = rs.getInt("campaign_held_count").coerceAtLeast(0),
                campaignCancelledCount = rs.getInt("campaign_cancelled_count"),
                campaignCancelledAmount = rs.getLong("campaign_cancelled_amount"),
                campaignBudgetLimit = rs.getObject("campaign_budget_limit", Long::class.javaObjectType),
                campaignHeldAmount = rs.getLong("campaign_held_amount"),
                campaignSpentAmount = rs.getLong("campaign_spent_amount"),
                campaignReleasedAmount = rs.getLong("campaign_released_amount"),
                systemReserveAmount = systemReserveAmount,
            )
        }
    }

            @Transactional(readOnly = true)
    fun listBudgetLedger(actor: AuthUser): List<BudgetLedgerItemResponse> {
        requireAdmin(actor)
        return jdbcTemplate.query(
            """SELECT l.id,
                      l.action,
                      l.amount,
                      l.campaign_id,
                      COALESCE(camp.code, v.code) AS campaign_code,
                      l.voucher_id,
                      CASE
                          WHEN camp.id IS NOT NULL THEN 'Chiến dịch ' || COALESCE(camp.code, '#' || camp.id)
                          WHEN v.source = 'SYSTEM_REWARD' THEN 'Voucher chào mừng (Giảm ' || v.value || CASE WHEN v.discount_type = 'PERCENT' THEN '%' ELSE 'đ' END || ')'
                          WHEN v.id IS NOT NULL THEN COALESCE(v.code, 'Voucher #' || v.id)
                          WHEN l.action = 'ISSUE_RESERVE' THEN 'Voucher chào mừng khách hàng mới (Hệ thống tự phát)'
                          ELSE NULL
                      END AS voucher_label,
                      CASE
                          WHEN c.id IS NOT NULL THEN COALESCE(NULLIF(TRIM(c.full_name), ''), 'Khách hàng') || ' (#' || c.id || ')'
                          ELSE NULL
                      END AS customer_name,
                      l.ride_id,
                      l.note,
                      l.created_at
               FROM promo_budget_ledger l
               LEFT JOIN vouchers camp ON camp.id = l.campaign_id
               LEFT JOIN vouchers v ON v.id = l.voucher_id
               LEFT JOIN customers c ON c.id = v.owner_customer_id
               ORDER BY l.id DESC
               LIMIT 50""".trimIndent(),
        ) { rs, _ ->
            val rawNote = rs.getString("note")
            val campaignId = rs.getObject("campaign_id", Long::class.javaObjectType)
            val voucherId = rs.getObject("voucher_id", Long::class.javaObjectType)
            val friendlyNote = when {
                rawNote == "Dự phòng phát hành voucher" && campaignId == null && voucherId == null ->
                    "Hệ thống trích dự phòng tự động cho gói voucher chào mừng khách hàng mới"
                else -> rawNote
            }
            BudgetLedgerItemResponse(
                id = rs.getLong("id"),
                action = rs.getString("action"),
                amount = rs.getLong("amount"),
                campaignId = campaignId,
                campaignCode = rs.getString("campaign_code"),
                voucherId = voucherId,
                voucherLabel = rs.getString("voucher_label"),
                customerName = rs.getString("customer_name"),
                rideId = rs.getObject("ride_id", Long::class.javaObjectType),
                note = friendlyNote,
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }
    }

    @Transactional(readOnly = true)
    fun listClaimants(actor: AuthUser, campaignId: Long): List<CampaignClaimantResponse> {
        requireAdmin(actor)
        return jdbcTemplate.query(
            """SELECT v.id AS voucher_id,
                      v.owner_customer_id AS customer_id,
                      COALESCE(NULLIF(TRIM(c.full_name), ''), 'Khách hàng #' || v.owner_customer_id) AS full_name,
                      COALESCE(NULLIF(TRIM(c.phone_number), ''), NULLIF(TRIM(c.email), ''), '—') AS phone,
                      COALESCE(v.valid_from, CURRENT_TIMESTAMP) AS claimed_at,
                      vu.ride_id,
                      vu.discount_amount,
                      CASE
                          WHEN r.status = 'COMPLETED' THEN 'USED'
                          WHEN vu.ride_id IS NOT NULL AND r.status NOT IN ('CANCELLED', 'COMPLETED') THEN 'HELD'
                          WHEN v.active = FALSE OR v.valid_to <= CURRENT_TIMESTAMP THEN 'EXPIRED'
                          ELSE 'CLAIMED'
                      END AS claim_status
               FROM vouchers v
               LEFT JOIN customers c ON c.id = v.owner_customer_id
               LEFT JOIN voucher_usages vu ON vu.voucher_id = v.id
               LEFT JOIN rides r ON r.id = vu.ride_id
               WHERE v.campaign_id = ? AND v.owner_customer_id IS NOT NULL
               ORDER BY v.id DESC""".trimIndent(),
            { rs, _ ->
                CampaignClaimantResponse(
                    voucherId = rs.getLong("voucher_id"),
                    customerId = rs.getLong("customer_id"),
                    fullName = rs.getString("full_name"),
                    phone = rs.getString("phone") ?: "—",
                    claimedAt = rs.getTimestamp("claimed_at").toInstant(),
                    status = rs.getString("claim_status"),
                    rideId = rs.getObject("ride_id", Long::class.javaObjectType),
                    discountAmount = rs.getObject("discount_amount", Long::class.javaObjectType),
                )
            },
            campaignId,
        )
    }

    @Transactional
    fun create(
        actor: AuthUser,
        request: CreateAdminVoucherRequest,
    ): AdminVoucherCreatedResponse {
        requireAdmin(actor)

        val normalizedCode = request.code
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotBlank() }

        val source: String
        val ownerCustomerId: Long?

        if (request.customerId == null) {
            if (normalizedCode == null) {
                throw BadRequestException(message = "Voucher công khai bắt buộc phải có mã")
            }
            if (!normalizedCode.matches(Regex("[A-Z0-9_-]{3,50}"))) {
                throw BadRequestException(
                    message = "Mã voucher chỉ được gồm chữ in hoa, số, dấu gạch ngang hoặc gạch dưới (3-50 ký tự)",
                )
            }
            source = "PUBLIC_CODE"
            ownerCustomerId = null
        } else {
            if (normalizedCode != null) {
                throw BadRequestException(message = "Voucher cá nhân không được có mã công khai")
            }
            source = "SYSTEM_REWARD"
            ownerCustomerId = request.customerId

            val exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM customers WHERE id = ?)",
                Boolean::class.java,
                ownerCustomerId,
            ) ?: false

            if (!exists) {
                throw BadRequestException(message = "Không tìm thấy khách hàng")
            }
        }

        val discountType = request.discountType.trim().uppercase()
        if (discountType !in setOf("PERCENT", "FIXED")) {
            throw BadRequestException(message = "discountType chỉ hỗ trợ PERCENT hoặc FIXED")
        }
        if (request.value <= 0) {
            throw BadRequestException(message = "Giá trị giảm phải lớn hơn 0")
        }
        if (discountType == "PERCENT" && request.value > 100) {
            throw BadRequestException(message = "Voucher phần trăm không được vượt quá 100%")
        }
        if (request.maxDiscount != null && request.maxDiscount <= 0) {
            throw BadRequestException(message = "Mức giảm tối đa phải lớn hơn 0")
        }
        if (request.minimumOrderAmount < 0) {
            throw BadRequestException(message = "Đơn tối thiểu không được âm")
        }
        if (request.usageLimit != null && request.usageLimit <= 0) {
            throw BadRequestException(message = "Tổng suất nhận phải lớn hơn 0")
        }

        val maxDiscount = request.maxDiscount ?: if (discountType == "FIXED") request.value else null
        val usageLimit = request.usageLimit ?: if (ownerCustomerId != null) 1 else null
        if (maxDiscount == null || usageLimit == null) {
            throw BadRequestException(message = "Voucher cần có mức giảm tối đa và tổng lượt để kiểm soát ngân sách")
        }

        val description = request.description.trim()
        if (ownerCustomerId == null && description.isBlank()) {
            throw BadRequestException(message = "Nhập mô tả để hiển thị trong thông báo ưu đãi")
        }
        if (description.length > 500) {
            throw BadRequestException(message = "Mô tả voucher tối đa 500 ký tự")
        }
        if (request.campaignBudgetLimit < 0) {
            throw BadRequestException(message = "Ngân sách chiến dịch không được âm")
        }

        val forecastPerUse = maxDiscount / 2
        val allocatedCampaignBudget = if (ownerCustomerId == null) {
            if (request.campaignBudgetLimit <= 0) {
                throw BadRequestException(message = "Ngân sách phân bổ cho chiến dịch phải lớn hơn 0")
            }
            if (request.campaignBudgetLimit < maxDiscount) {
                throw BadRequestException(message = "Ngân sách chiến dịch không được nhỏ hơn mức giảm tối đa của 1 voucher (${maxDiscount}đ)")
            }
            request.campaignBudgetLimit
        } else {
            try {
                Math.multiplyExact(forecastPerUse, usageLimit.toLong())
            } catch (_: ArithmeticException) {
                throw BadRequestException(message = "Tổng dự phòng vượt giới hạn cho phép")
            }
        }

        if (request.validTo <= request.validFrom) {
            throw BadRequestException(message = "Thời gian kết thúc phải sau thời gian bắt đầu")
        }

        // Nếu phát hành chiến dịch công khai mới -> kết thúc chiến dịch cũ & hoàn phần dư về quỹ tổng trước khi kiểm tra quỹ
        if (ownerCustomerId == null) {
            promoBudget?.closeActiveCampaignsAndReturnSurplus()
            val currentSummary = promoBudget?.summary()
            if (currentSummary != null && allocatedCampaignBudget > currentSummary.availableAmount) {
                throw BadRequestException(
                    message = "Ngân sách chiến dịch (${allocatedCampaignBudget}đ) vượt quá quỹ tổng khả dụng (${currentSummary.availableAmount}đ)",
                )
            }
        } else if (promoBudget?.reserveIssuance(allocatedCampaignBudget) == false) {
            throw BadRequestException(message = "Quỹ khuyến mãi khả dụng không đủ để phát hành voucher")
        }

        val id = try {
            jdbcTemplate.query(
                """
                INSERT INTO vouchers (
                    code, discount_type, value, max_discount, minimum_order_amount,
                    usage_count, usage_limit, valid_from, valid_to, active,
                    owner_customer_id, source, milestone, allowed_payment_method,
                    forecast_reserve_per_use, description, campaign_budget_limit, campaign_title
                )
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, TRUE, ?, ?, 0, ?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent(),
                { row: ResultSet, _: Int -> row.getLong("id") },
                normalizedCode,
                discountType,
                request.value,
                maxDiscount,
                request.minimumOrderAmount,
                usageLimit,
                Timestamp.from(request.validFrom),
                Timestamp.from(request.validTo),
                ownerCustomerId,
                source,
                request.allowedPaymentMethod?.name,
                forecastPerUse,
                description,
                if (ownerCustomerId == null) allocatedCampaignBudget else null,
                request.campaignTitle?.trim()?.takeIf { it.isNotEmpty() },
            ).single()
        } catch (_: DataIntegrityViolationException) {
            throw BadRequestException(message = "Mã voucher đã tồn tại hoặc dữ liệu không hợp lệ")
        }

        if (ownerCustomerId == null) {
            jdbcTemplate.update(
                "INSERT INTO voucher_campaign_budget(campaign_id, budget_limit) VALUES (?, ?)",
                id,
                allocatedCampaignBudget,
            )
            if (promoBudget?.reserveIssuance(allocatedCampaignBudget, id) == false) {
                throw BadRequestException(message = "Ngân sách chiến dịch vượt quá quỹ tổng khả dụng")
            }
            val normalizedTitle = request.campaignTitle?.trim()?.takeIf { it.isNotEmpty() }
            val discountText = if (discountType == "PERCENT") {
                "Giảm ${request.value}% (tối đa ${String.format("%,d", maxDiscount)}đ)"
            } else {
                "Giảm ${String.format("%,d", request.value)}đ"
            }
            events?.publishEvent(
                VoucherCampaignPublished(
                    id = id,
                    code = normalizedCode!!,
                    campaignTitle = normalizedTitle,
                    discountSummary = discountText,
                    claimLimit = usageLimit,
                    description = description,
                ),
            )
        }

        return AdminVoucherCreatedResponse(
            id = id,
            code = normalizedCode,
            campaignTitle = request.campaignTitle?.trim()?.takeIf { it.isNotEmpty() },
            source = source,
            active = true,
            ownerCustomerId = ownerCustomerId,
            discountType = discountType,
            value = request.value,
            maxDiscount = maxDiscount,
            minimumOrderAmount = request.minimumOrderAmount,
            usageLimit = usageLimit,
            validFrom = request.validFrom,
            validTo = request.validTo,
            allowedPaymentMethod = request.allowedPaymentMethod?.name,
            description = description,
        )
    }

    @Transactional
    fun setCampaignActive(actor: AuthUser, campaignId: Long, active: Boolean) {
        requireAdmin(actor)
        val budget = promoBudget ?: throw BadRequestException(message = "Dịch vụ quỹ khuyến mãi chưa sẵn sàng")
        if (active) {
            budget.resumeCampaign(campaignId)
        } else {
            budget.pauseCampaign(campaignId)
        }
    }

    private fun requireAdmin(actor: AuthUser) {
        if (actor.role != Role.ADMIN) {
            throw ForbiddenException(message = "Chỉ quản trị viên được quản lý voucher")
        }
    }
}