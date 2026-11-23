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
        return jdbc.queryForObject(
            "SELECT budget_limit, held_amount, spent_amount, issuance_reserved FROM promo_budget WHERE id = 1",
            { rs, _ -> PromoBudgetSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) },
        )!!
    }

    @Transactional
    fun setLimit(amount: Long) {
        if (amount < 0) throw BadRequestException(message = "Ngân sách không được âm")
        releaseExpiredCampaignBudgets()
        val current = jdbc.queryForObject(
            "SELECT budget_limit, held_amount, spent_amount, issuance_reserved FROM promo_budget WHERE id = 1 FOR UPDATE",
            { rs, _ -> PromoBudgetSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) },
        )!!
        if (amount < current.heldAmount + current.spentAmount + current.issuanceReserved) {
            throw BadRequestException(message = "Hạn mức không được thấp hơn ngân sách đang giữ, đã chi và đã phân bổ")
        }
        val updated = jdbc.update(
            "UPDATE promo_budget SET budget_limit = ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1 AND ? >= held_amount + spent_amount + issuance_reserved",
            amount,
            amount,
        )
        if (updated != 1) {
            throw BadRequestException(message = "Hạn mức không được thấp hơn ngân sách đang giữ, đã chi và đã phân bổ")
        }
        jdbc.update("INSERT INTO promo_budget_ledger(action, amount, note) VALUES ('BUDGET_SET', ?, 'Admin cập nhật hạn mức quỹ tổng')", amount)
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

        if (budget.availableAmount + forecast < amount) {
            throw BadRequestException(ErrorCode.VOUCHER_INVALID, "Voucher không khả dụng do ngân sách khuyến mãi không đủ")
        }
        jdbc.update(
            "UPDATE promo_budget SET issuance_reserved = GREATEST(issuance_reserved - ?, 0), held_amount = held_amount + ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1",
            forecast,
            amount,
        )
        jdbc.update(
            "INSERT INTO promo_budget_ledger(ride_id, voucher_id, campaign_id, action, amount, forecast_amount) VALUES (?, ?, ?, 'HOLD', ?, ?)",
            rideId,
            voucherId,
            campaignId,
            amount,
            forecast,
        )
    }

    @Transactional
    fun release(rideId: Long) {
        val hold = jdbc.query(
            "SELECT amount, forecast_amount, voucher_id, campaign_id FROM promo_budget_ledger WHERE ride_id = ? AND action = 'HOLD'",
            { rs, _ -> arrayOf<Any?>(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getObject(4, Long::class.javaObjectType)) },
            rideId,
        ).firstOrNull() ?: return

        val released = jdbc.update(
            "INSERT INTO promo_budget_ledger(ride_id, voucher_id, campaign_id, action, amount, forecast_amount) VALUES (?, ?, ?, 'RELEASE', ?, ?) ON CONFLICT DO NOTHING",
            rideId,
            hold[2],
            hold[3],
            hold[0],
            hold[1],
        )
        if (released == 1) {
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
            val forecastAmount = hold[1] as Long
            val canRestoreAmount = if (canRestore) forecastAmount else 0L
            jdbc.update(
                "UPDATE promo_budget SET held_amount = GREATEST(held_amount - ?, 0), issuance_reserved = issuance_reserved + ?, updated_at = CURRENT_TIMESTAMP WHERE id = 1",
                holdAmount,
                canRestoreAmount,
            )
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
        val hold = jdbc.query(
            "SELECT amount, campaign_id, voucher_id FROM promo_budget_ledger WHERE ride_id = ? AND action = 'HOLD'",
            { rs, _ -> arrayOf<Any?>(rs.getLong(1), rs.getObject(2, Long::class.javaObjectType), rs.getLong(3)) },
            rideId,
        ).firstOrNull() ?: return

        val amount = hold[0] as Long
        val campaignId = hold[1] as Long?
        val spent = jdbc.update(
            """INSERT INTO promo_budget_ledger(ride_id, voucher_id, campaign_id, action, amount)
               SELECT ?, ?, ?, 'SPEND', ?
               WHERE NOT EXISTS (
                   SELECT 1 FROM promo_budget_ledger WHERE ride_id = ? AND action = 'RELEASE'
               )
               ON CONFLICT DO NOTHING""",
            rideId,
            hold[2],
            campaignId,
            amount,
            rideId,
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