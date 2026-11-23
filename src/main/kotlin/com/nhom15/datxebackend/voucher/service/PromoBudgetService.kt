package com.nhom15.datxebackend.voucher.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.BadRequestException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class PromoBudgetSummary(
    val budgetLimit: Long,
    val heldAmount: Long,
    val spentAmount: Long,
    val issuanceReserved: Long = 0,
) {
    val availableAmount: Long get() = budgetLimit - heldAmount - spentAmount - issuanceReserved
}

@Service
class PromoBudgetService(private val jdbc: JdbcTemplate) {
    @Transactional
    fun summary(): PromoBudgetSummary {
        releaseExpiredCampaignBudgets()
        releaseExpiredPersonalRewardReserves()
        return jdbc.queryForObject(
            "SELECT budget_limit, held_amount, spent_amount, issuance_reserved FROM promo_budget WHERE id = 1",
            { rs, _ -> PromoBudgetSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) },
        )!!
    }

    @Transactional
    fun setLimit(amount: Long) {
        if (amount < 0) throw BadRequestException(message = "Ngân sách không được âm")
        val current = summary()
        if (amount < current.heldAmount + current.spentAmount + current.issuanceReserved) {
            throw BadRequestException(message = "Hạn mức không được thấp hơn ngân sách đang giữ, đã chi và đã phân bổ")
        }
        val delta = amount - current.budgetLimit
        val sign = if (delta >= 0) "+" else ""
        // promo_budget_ledger.amount có CHECK (amount >= 0) nên không lưu được delta âm.
        // Lưu độ lớn delta ở amount và mã hoá dấu + giá trị cũ/mới trong note để admin vẫn tái dựng được.
        val note = "Admin cập nhật hạn mức quỹ tổng: ${current.budgetLimit}đ -> ${amount}đ (delta $sign${delta}đ)"
        jdbc.update("UPDATE promo_budget SET budget_limit = ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1", amount)
        jdbc.update("INSERT INTO promo_budget_ledger(action, amount, note) VALUES ('BUDGET_SET', ?, ?)", kotlin.math.abs(delta), note)
    }

    @Transactional
    fun closeActiveCampaignsAndReturnSurplus(): Long {
        releaseExpiredCampaignBudgets()
        val activeCampaigns = jdbc.query(
            """SELECT v.id, COALESCE(cb.budget_limit - cb.held_amount - cb.spent_amount - cb.released_amount, 0) AS remaining
               FROM vouchers v
               LEFT JOIN voucher_campaign_budget cb ON cb.campaign_id = v.id
               WHERE v.campaign_id IS NULL
                 AND v.source = 'PUBLIC_CODE'
                 AND v.active = TRUE
               FOR UPDATE OF v""".trimIndent(),
            { rs, _ -> rs.getLong(1) to rs.getLong(2).coerceAtLeast(0L) },
        )

        var totalReturned = 0L
        for ((campaignId, remaining) in activeCampaigns) {
            jdbc.update(
                "UPDATE vouchers SET active = FALSE, valid_to = LEAST(valid_to, CURRENT_TIMESTAMP) WHERE id = ?",
                campaignId,
            )
            jdbc.update(
                "UPDATE vouchers SET active = FALSE, valid_to = LEAST(valid_to, CURRENT_TIMESTAMP) WHERE campaign_id = ? AND usage_count = 0",
                campaignId,
            )
            if (remaining > 0) {
                val updated = jdbc.update(
                    "UPDATE voucher_campaign_budget SET released_amount = released_amount + ? WHERE campaign_id = ? AND budget_limit - held_amount - spent_amount - released_amount >= ?",
                    remaining,
                    campaignId,
                    remaining,
                )
                if (updated == 1) {
                    jdbc.update(
                        "UPDATE promo_budget SET issuance_reserved = GREATEST(issuance_reserved - ?, 0), updated_at = CURRENT_TIMESTAMP WHERE id = 1",
                        remaining,
                    )
                    jdbc.update(
                        "INSERT INTO promo_budget_ledger(campaign_id, action, amount, note) VALUES (?, 'RELEASE', ?, 'Kết thúc chiến dịch cũ và hoàn phần dư về quỹ tổng')",
                        campaignId,
                        remaining,
                    )
                    totalReturned += remaining
                }
            }
        }
        return totalReturned
    }

    @Transactional
    fun pauseCampaign(campaignId: Long) {
        releaseExpiredCampaignBudgets()
        val row = jdbc.query(
            """SELECT v.active, COALESCE(cb.budget_limit - cb.held_amount - cb.spent_amount - cb.released_amount, 0) AS remaining
               FROM vouchers v
               LEFT JOIN voucher_campaign_budget cb ON cb.campaign_id = v.id
               WHERE v.id = ? AND v.campaign_id IS NULL AND v.source = 'PUBLIC_CODE'
               FOR UPDATE OF v""".trimIndent(),
            { rs, _ -> rs.getBoolean(1) to rs.getLong(2).coerceAtLeast(0L) },
            campaignId,
        ).firstOrNull() ?: throw BadRequestException(message = "Không tìm thấy chiến dịch")

        val (isActive, remaining) = row
        if (!isActive) return

        jdbc.update("UPDATE vouchers SET active = FALSE WHERE id = ?", campaignId)
        jdbc.update("UPDATE vouchers SET active = FALSE WHERE campaign_id = ? AND usage_count = 0", campaignId)

        if (remaining > 0) {
            val updated = jdbc.update(
                "UPDATE voucher_campaign_budget SET released_amount = released_amount + ? WHERE campaign_id = ? AND budget_limit - held_amount - spent_amount - released_amount >= ?",
                remaining,
                campaignId,
                remaining,
            )
            if (updated == 1) {
                jdbc.update(
                    "UPDATE promo_budget SET issuance_reserved = GREATEST(issuance_reserved - ?, 0), updated_at = CURRENT_TIMESTAMP WHERE id = 1",
                    remaining,
                )
                jdbc.update(
                    "INSERT INTO promo_budget_ledger(campaign_id, action, amount, note) VALUES (?, 'RELEASE', ?, 'Admin tạm dừng chiến dịch và hoàn phần dư về quỹ tổng')",
                    campaignId,
                    remaining,
                )
            }
        }
    }

    @Transactional
    fun resumeCampaign(campaignId: Long) {
        releaseExpiredCampaignBudgets()
        val info = jdbc.query(
            """SELECT v.active,
                      (v.valid_to <= CURRENT_TIMESTAMP) AS expired,
                      COALESCE(cb.budget_limit, 0) AS budget_limit,
                      COALESCE(cb.held_amount, 0) AS held_amount,
                      COALESCE(cb.spent_amount, 0) AS spent_amount,
                      COALESCE(cb.released_amount, 0) AS released_amount,
                      (cb.campaign_id IS NOT NULL) AS has_budget_row
               FROM vouchers v
               LEFT JOIN voucher_campaign_budget cb ON cb.campaign_id = v.id
               WHERE v.id = ? AND v.campaign_id IS NULL AND v.source = 'PUBLIC_CODE'
               FOR UPDATE OF v""".trimIndent(),
            { rs, _ ->
                arrayOf<Any>(
                    rs.getBoolean("active"),
                    rs.getBoolean("expired"),
                    rs.getLong("budget_limit"),
                    rs.getLong("held_amount"),
                    rs.getLong("spent_amount"),
                    rs.getLong("released_amount"),
                    rs.getBoolean("has_budget_row"),
                )
            },
            campaignId,
        ).firstOrNull() ?: throw BadRequestException(message = "Không tìm thấy chiến dịch")

        val isActive = info[0] as Boolean
        val expired = info[1] as Boolean
        val budgetLimit = info[2] as Long
        val heldAmount = info[3] as Long
        val spentAmount = info[4] as Long
        val releasedAmount = info[5] as Long
        val hasBudgetRow = info[6] as Boolean

        if (isActive) return
        if (expired) {
            throw BadRequestException(message = "Chiến dịch đã hết thời gian hiệu lực, không thể tiếp tục")
        }

        val otherActives = jdbc.query(
            "SELECT id FROM vouchers WHERE id <> ? AND campaign_id IS NULL AND source = 'PUBLIC_CODE' AND active = TRUE",
            { rs, _ -> rs.getLong(1) },
            campaignId,
        )
        otherActives.forEach { pauseCampaign(it) }

        val neededFromFund = (budgetLimit - heldAmount - spentAmount).coerceAtLeast(0L)
        if (hasBudgetRow && releasedAmount > 0 && neededFromFund > 0) {
            val budget = jdbc.queryForObject(
                "SELECT budget_limit, held_amount, spent_amount, issuance_reserved FROM promo_budget WHERE id = 1 FOR UPDATE",
            ) { rs, _ -> PromoBudgetSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }!!

            if (budget.availableAmount < releasedAmount) {
                throw BadRequestException(
                    message = "Quỹ tổng khả dụng (${budget.availableAmount}đ) không đủ để cấp lại ${releasedAmount}đ cho chiến dịch này",
                )
            }
            jdbc.update(
                "UPDATE voucher_campaign_budget SET released_amount = 0 WHERE campaign_id = ?",
                campaignId,
            )
            jdbc.update(
                "UPDATE promo_budget SET issuance_reserved = issuance_reserved + ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1",
                releasedAmount,
            )
            jdbc.update(
                "INSERT INTO promo_budget_ledger(campaign_id, action, amount, note) VALUES (?, 'ISSUE_RESERVE', ?, 'Admin tiếp tục chiến dịch và tái phân bổ từ quỹ tổng')",
                campaignId,
                releasedAmount,
            )
        }

        jdbc.update("UPDATE vouchers SET active = TRUE WHERE id = ?", campaignId)
        jdbc.update(
            "UPDATE vouchers SET active = TRUE WHERE campaign_id = ? AND usage_count = 0 AND valid_to > CURRENT_TIMESTAMP",
            campaignId,
        )
    }

    @Transactional
    fun reserveIssuance(amount: Long, campaignId: Long? = null): Boolean {
        if (amount <= 0) return true
        releaseExpiredCampaignBudgets()
        releaseExpiredPersonalRewardReserves()
        val budget = jdbc.queryForObject(
            "SELECT budget_limit, held_amount, spent_amount, issuance_reserved FROM promo_budget WHERE id = 1 FOR UPDATE",
        ) { rs, _ -> PromoBudgetSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }!!

        if (budget.availableAmount < amount) return false
        jdbc.update(
            "UPDATE promo_budget SET issuance_reserved = issuance_reserved + ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1",
            amount,
        )
        jdbc.update(
            "INSERT INTO promo_budget_ledger(campaign_id, action, amount, note) VALUES (?, 'ISSUE_RESERVE', ?, ?)",
            campaignId,
            amount,
            if (campaignId == null) "Dự phòng phát hành voucher" else "Phân bổ ngân sách chiến dịch #$campaignId",
        )
        return true
    }

    @Transactional
    fun hold(rideId: Long, voucherId: Long, amount: Long, forecast: Long = 0) {
        if (amount <= 0) return
        val budget = jdbc.queryForObject(
            "SELECT budget_limit, held_amount, spent_amount, issuance_reserved FROM promo_budget WHERE id = 1 FOR UPDATE",
        ) { rs, _ -> PromoBudgetSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }!!

        val campaignId = jdbc.query(
            "SELECT campaign_id FROM vouchers WHERE id = ?",
            { rs, _ -> rs.getObject(1, Long::class.javaObjectType) },
            voucherId,
        ).firstOrNull()

        if (campaignId != null) {
            val hasCampaignBudget = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM voucher_campaign_budget WHERE campaign_id = ?)",
                Boolean::class.java,
                campaignId,
            ) ?: false

            if (hasCampaignBudget) {
                if (budget.issuanceReserved < amount) {
                    throw BadRequestException(ErrorCode.VOUCHER_INVALID, "Voucher không khả dụng do quỹ khuyến mãi không đủ")
                }
                val changed = jdbc.update(
                    "UPDATE voucher_campaign_budget SET held_amount = held_amount + ? WHERE campaign_id = ? AND budget_limit - held_amount - spent_amount - released_amount >= ?",
                    amount,
                    campaignId,
                    amount,
                )
                if (changed != 1) {
                    throw BadRequestException(ErrorCode.VOUCHER_INVALID, "Voucher không khả dụng do ngân sách chiến dịch đã hết")
                }
                jdbc.update(
                    "UPDATE promo_budget SET issuance_reserved = issuance_reserved - ?, held_amount = held_amount + ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1",
                    amount,
                    amount,
                )
                jdbc.update(
                    "INSERT INTO promo_budget_ledger(ride_id, voucher_id, campaign_id, action, amount, forecast_amount) VALUES (?, ?, ?, 'HOLD', ?, ?)",
                    rideId,
                    voucherId,
                    campaignId,
                    amount,
                    amount,
                )
                return
            }
        }

        // forecast_amount của dòng HOLD là số tiền THỰC bị trừ khỏi issuance_reserved,
        // không phải forecast danh nghĩa: consumed = min(issuance_reserved, forecast).
        // release() đọc lại chính giá trị này để hoàn đúng số đã trích (legacy forecast = 0 -> không trích).
        val consumedReserve = budget.issuanceReserved.coerceAtMost(forecast).coerceAtLeast(0L)
        if (budget.availableAmount + consumedReserve < amount) {
            throw BadRequestException(ErrorCode.VOUCHER_INVALID, "Voucher không khả dụng do ngân sách khuyến mãi không đủ")
        }
        jdbc.update(
            "UPDATE promo_budget SET issuance_reserved = issuance_reserved - ?, held_amount = held_amount + ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1",
            consumedReserve,
            amount,
        )
        jdbc.update(
            "INSERT INTO promo_budget_ledger(ride_id, voucher_id, campaign_id, action, amount, forecast_amount, note) VALUES (?, ?, ?, 'HOLD', ?, ?, ?)",
            rideId,
            voucherId,
            campaignId,
            amount,
            consumedReserve,
            "Dự kiến ${forecast}đ, thực trích ${consumedReserve}đ từ quỹ dự phòng cho giữ chỗ thanh toán",
        )
    }

    @Transactional
    fun release(rideId: Long) {
        // Khoá đúng dòng ngân sách: release/spend cùng một HOLD phải nối hàng, không được cùng trừ held.
        // Đọc luôn snapshot để clamp số hoàn trả (HOLD cũ có thể ghi forecast danh nghĩa lớn hơn số thực đã trích).
        val budget = jdbc.queryForObject(
            "SELECT budget_limit, held_amount, spent_amount, issuance_reserved FROM promo_budget WHERE id = 1 FOR UPDATE",
        ) { rs, _ -> PromoBudgetSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) }!!
        val hold = jdbc.query(
            "SELECT amount, forecast_amount, voucher_id, campaign_id FROM promo_budget_ledger WHERE ride_id = ? AND action = 'HOLD'",
            { rs, _ -> arrayOf<Any?>(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getObject(4, Long::class.javaObjectType)) },
            rideId,
        ).firstOrNull() ?: return

        // SPEND và RELEASE loại trừ nhau cho cùng một ride: đã chi thì không hoàn held/reserve lần nữa.
        val alreadySpent = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM promo_budget_ledger WHERE ride_id = ? AND action = 'SPEND')",
            Boolean::class.java,
            rideId,
        ) ?: false
        if (alreadySpent) return

        val campaignId = hold[3] as Long?
        val canRestore = if (campaignId != null) {
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM vouchers v JOIN vouchers camp ON camp.id = v.campaign_id WHERE v.id = ? AND v.active AND v.valid_to > CURRENT_TIMESTAMP AND camp.active AND camp.valid_to > CURRENT_TIMESTAMP)",
                Boolean::class.java,
                hold[2],
            ) ?: false
        } else {
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM vouchers WHERE id = ? AND active AND valid_to > CURRENT_TIMESTAMP)",
                Boolean::class.java,
                hold[2],
            ) ?: false
        }

        val holdAmount = hold[0] as Long
        // forecast_amount trên dòng HOLD luôn là số thực đã trừ khỏi issuance_reserved:
        // campaign = amount, non-campaign = min(issuance_reserved trước hold, forecast).
        val consumedReserve = hold[1] as Long

        val released = jdbc.update(
            "INSERT INTO promo_budget_ledger(ride_id, voucher_id, campaign_id, action, amount, forecast_amount, note) VALUES (?, ?, ?, 'RELEASE', ?, ?, ?) ON CONFLICT DO NOTHING",
            rideId,
            hold[2],
            hold[3],
            holdAmount,
            consumedReserve,
            if (canRestore) "Hoàn ${consumedReserve}đ quỹ dự phòng đã trích khi giữ chỗ" else "Không hoàn quỹ dự phòng: voucher không còn hiệu lực",
        )
        if (released == 1) {
            // HOLD rows do cũ (trước khi sửa H2) lưu forecast danh nghĩa dù GREATEST(...,0)
            // đã chặn một phần trích. clamp theo available hiện tại + held vừa được hoàn để
            // không bao giờ "tạo tiền" vượt budget_limit khi release các row lịch sử này.
            val maxSafeRestore = (budget.availableAmount + holdAmount).coerceAtLeast(0L)
            // Voucher cá nhân đã hết hạn/bị ngưng hoạt động: khoản thực trích lúc HOLD vẫn
            // phải hoàn dù voucher không còn dùng được. Nếu bỏ qua, V47 sweeper cũng loại
            // voucher này vì còn dòng HOLD trong ledger => issuance_reserved treo vĩnh viễn.
            val restoreExpiredPersonalReserve = !canRestore && campaignId == null && consumedReserve > 0
            val canRestoreAmount = when {
                canRestore || restoreExpiredPersonalReserve -> minOf(consumedReserve, maxSafeRestore)
                else -> 0L
            }
            jdbc.update(
                "UPDATE promo_budget SET held_amount = GREATEST(held_amount - ?, 0), issuance_reserved = issuance_reserved + ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1",
                holdAmount,
                canRestoreAmount,
            )
            if (restoreExpiredPersonalReserve && canRestoreAmount > 0) {
                // Đánh dấu đã hoàn để sweeper V47 không cộng lại lần nữa.
                jdbc.update("UPDATE vouchers SET reserve_released = TRUE WHERE id = ?", hold[2])
            }
            if (campaignId != null) {
                if (canRestore) {
                    jdbc.update(
                        "UPDATE voucher_campaign_budget SET held_amount = GREATEST(held_amount - ?, 0) WHERE campaign_id = ?",
                        holdAmount,
                        campaignId,
                    )
                } else {
                    jdbc.update(
                        "UPDATE voucher_campaign_budget SET held_amount = GREATEST(held_amount - ?, 0), released_amount = released_amount + ? WHERE campaign_id = ?",
                        holdAmount,
                        holdAmount,
                        campaignId,
                    )
                }
            }
        }
    }

    @Transactional
    fun spend(rideId: Long) {
        // Khoá đúng dòng ngân sách: release/spend cùng một HOLD phải nối hàng, không được cùng trừ held.
        jdbc.queryForObject("SELECT id FROM promo_budget WHERE id = 1 FOR UPDATE", Long::class.java)
        val hold = jdbc.query(
            "SELECT amount, forecast_amount, campaign_id, voucher_id FROM promo_budget_ledger WHERE ride_id = ? AND action = 'HOLD'",
            { rs, _ -> arrayOf<Any?>(rs.getLong(1), rs.getLong(2), rs.getObject(3, Long::class.javaObjectType), rs.getLong(4)) },
            rideId,
        ).firstOrNull() ?: return

        // Đã hoàn (cancel) thì không được chuyển tiếp held -> spent lần nữa.
        val alreadyReleased = jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM promo_budget_ledger WHERE ride_id = ? AND action = 'RELEASE')",
            Boolean::class.java,
            rideId,
        ) ?: false
        if (alreadyReleased) return

        val amount = hold[0] as Long
        val consumedReserve = hold[1] as Long
        val campaignId = hold[2] as Long?
        val spent = jdbc.update(
            "INSERT INTO promo_budget_ledger(ride_id, voucher_id, campaign_id, action, amount, forecast_amount) VALUES (?, ?, ?, 'SPEND', ?, ?) ON CONFLICT DO NOTHING",
            rideId,
            hold[3],
            campaignId,
            amount,
            consumedReserve,
        )
        if (spent == 1) {
            jdbc.update(
                "UPDATE promo_budget SET held_amount = GREATEST(held_amount - ?, 0), spent_amount = spent_amount + ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1",
                amount,
                amount,
            )
            if (campaignId != null) {
                jdbc.update(
                    "UPDATE voucher_campaign_budget SET held_amount = GREATEST(held_amount - ?, 0), spent_amount = spent_amount + ? WHERE campaign_id = ?",
                    amount,
                    amount,
                    campaignId,
                )
            }
        }
    }

    /**
     * Voucher thưởng cá nhân hết hạn mà chưa từng giữ/chi thì không bao giờ tiêu dự phòng phát hành.
     * Cờ reserve_released (V47) đảm bảo mỗi voucher chỉ hoàn lại quỹ đúng một lần.
     */
    @Transactional
    fun releaseExpiredPersonalRewardReserves() {
        val expired = jdbc.query(
            """SELECT v.id, v.forecast_reserve_per_use
               FROM vouchers v
               WHERE v.source = 'SYSTEM_REWARD'
                 AND v.campaign_id IS NULL
                 AND v.forecast_reserve_per_use > 0
                 AND v.valid_to <= CURRENT_TIMESTAMP
                 AND v.reserve_released = FALSE
                 AND NOT EXISTS (
                     SELECT 1 FROM promo_budget_ledger l
                     WHERE l.voucher_id = v.id AND l.action IN ('HOLD', 'SPEND')
                 )
               FOR UPDATE OF v""".trimIndent(),
            { rs, _ -> rs.getLong(1) to rs.getLong(2) },
        )

        expired.forEach { (voucherId, forecast) ->
            // Khoá quỹ tổng trước khi trừ để không race với hold/spend/reserve cùng lúc.
            jdbc.queryForObject("SELECT id FROM promo_budget WHERE id = 1 FOR UPDATE", Long::class.java)
            val released = jdbc.update(
                "UPDATE vouchers SET reserve_released = TRUE WHERE id = ? AND reserve_released = FALSE",
                voucherId,
            )
            if (released != 1) return@forEach
            jdbc.update(
                "UPDATE promo_budget SET issuance_reserved = GREATEST(issuance_reserved - ?, 0), updated_at = CURRENT_TIMESTAMP WHERE id = 1",
                forecast,
            )
            jdbc.update(
                "INSERT INTO promo_budget_ledger(voucher_id, action, amount, note) VALUES (?, 'RELEASE', ?, 'Hoàn dự phòng phát hành voucher cá nhân hết hạn')",
                voucherId,
                forecast,
            )
        }
    }

    private fun releaseExpiredCampaignBudgets() {
        jdbc.query(
            "SELECT cb.budget_limit - cb.held_amount - cb.spent_amount - cb.released_amount, cb.campaign_id FROM voucher_campaign_budget cb JOIN vouchers v ON v.id = cb.campaign_id WHERE v.valid_to <= CURRENT_TIMESTAMP AND cb.budget_limit > cb.held_amount + cb.spent_amount + cb.released_amount FOR UPDATE OF cb",
            { rs, _ -> rs.getLong(1) to rs.getLong(2) },
        ).forEach { (amount, campaignId) ->
            if (amount <= 0) return@forEach
            val released = jdbc.update(
                "UPDATE voucher_campaign_budget SET released_amount = released_amount + ? WHERE campaign_id = ? AND budget_limit - held_amount - spent_amount - released_amount >= ?",
                amount,
                campaignId,
                amount,
            )
            if (released == 1) {
                jdbc.update("UPDATE vouchers SET active = FALSE WHERE id = ? OR campaign_id = ?", campaignId, campaignId)
                jdbc.update(
                    "UPDATE promo_budget SET issuance_reserved = GREATEST(issuance_reserved - ?, 0), updated_at = CURRENT_TIMESTAMP WHERE id = 1",
                    amount,
                )
                jdbc.update(
                    "INSERT INTO promo_budget_ledger(campaign_id, action, amount, note) VALUES (?, 'RELEASE', ?, 'Hoàn phần ngân sách chiến dịch đã hết hạn về quỹ tổng')",
                    campaignId,
                    amount,
                )
            }
        }
    }
}