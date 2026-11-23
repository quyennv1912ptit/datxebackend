package com.nhom15.datxebackend.voucher.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class VoucherGrantService(
    private val jdbcTemplate: JdbcTemplate,
    private val promoBudget: PromoBudgetService? = null,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun grantWelcome(customerId: Long): Boolean {
        return grant(customerId, 0, VoucherRewardPolicy.WELCOME)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun grantMilestoneIfReached(customerId: Long): Boolean {
        val completed = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM rides
            WHERE customer_id = ?
              AND status = 'COMPLETED'
            """.trimIndent(),
            Long::class.java,
            customerId,
        ) ?: 0L

        // Chọn mốc CAO NHẤT <= completed mà khách chưa nhận thay vì khớp đúng completed:
        // nếu một mốc bị bỏ sót (chuyến 6/11/21) thì các chuyến sau tự phục hồi mà không cấp trùng.
        val milestone = VoucherRewardPolicy.milestonesDesc()
            .firstOrNull { it <= completed && !alreadyGranted(customerId, it) }
            ?: return false
        val template = VoucherRewardPolicy.milestoneReward(milestone)
            ?: return false

        return grant(customerId, milestone.toInt(), template)
    }

    private fun alreadyGranted(customerId: Long, milestone: Long): Boolean = jdbcTemplate.queryForObject(
        """
        SELECT EXISTS (
            SELECT 1
            FROM vouchers
            WHERE owner_customer_id = ?
              AND source = 'SYSTEM_REWARD'
              AND milestone = ?
        )
        """.trimIndent(),
        Boolean::class.javaObjectType,
        customerId,
        milestone,
    ) ?: false

    private fun grant(
        customerId: Long,
        milestone: Int,
        template: VoucherTemplate,
    ): Boolean {

        // Khoa dong customer de tranh cap voucher trung khi request dong thoi.
        jdbcTemplate.queryForObject(
            """
            SELECT id
            FROM customers
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
            Long::class.java,
            customerId,
        ) ?: return false

        // Kiểm tra lại trong grant(): grantMilestoneIfReached và các luồng khác có thể cùng chọn một mốc.
        if (alreadyGranted(customerId, milestone.toLong())) return false
        val forecastPerUse = (template.maxDiscount ?: template.value) / 2
        if (promoBudget?.reserveIssuance(forecastPerUse) == false) return false

        val inserted = jdbcTemplate.queryForList(
            """
            INSERT INTO vouchers (
                code,
                discount_type,
                value,
                max_discount,
                minimum_order_amount,
                usage_count,
                usage_limit,
                valid_from,
                valid_to,
                active,
                owner_customer_id,
                source,
                milestone,
                allowed_payment_method,
                forecast_reserve_per_use
            )
            VALUES (
                NULL,
                ?,
                ?,
                ?,
                ?,
                0,
                1,
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP + (CAST(? AS double precision) * interval '1 day'),
                TRUE,
                ?,
                'SYSTEM_REWARD',
                ?,
                NULL,
                ?
            )
            RETURNING id
            """.trimIndent(),
            Long::class.java,
            template.discountType,
            template.value,
            template.maxDiscount,
            template.minimumOrderAmount,
            template.validDays,
            customerId,
            milestone,
            forecastPerUse,
        )

        return inserted.isNotEmpty()
    }
}
