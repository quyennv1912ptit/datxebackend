package com.nhom15.datxebackend.voucher.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.voucher.dto.AppliedVoucher
import com.nhom15.datxebackend.voucher.dto.VoucherResponse
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.ResultSet
import java.time.Instant

@Service
class VoucherService(private val jdbcTemplate: JdbcTemplate) {

    @Transactional(readOnly = true)
    fun listAvailable(actor: AuthUser): List<VoucherResponse> {
        requireCustomer(actor)

        return jdbcTemplate.query(
            """
            SELECT v.id, v.code, v.discount_type, v.value, v.max_discount,
                   v.minimum_order_amount, v.usage_count, v.usage_limit,
                   v.valid_from, v.valid_to, v.source
            FROM vouchers v
            WHERE v.active = TRUE
              AND v.valid_from <= CURRENT_TIMESTAMP
              AND v.valid_to > CURRENT_TIMESTAMP
              AND (v.usage_limit IS NULL OR v.usage_count < v.usage_limit)
              AND (
                  (v.source = 'PUBLIC_CODE' AND v.owner_customer_id IS NULL)
                  OR
                  (v.source = 'SYSTEM_REWARD' AND v.owner_customer_id = ?)
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM voucher_usages vu
                  WHERE vu.voucher_id = v.id
                    AND vu.customer_id = ?
              )
            ORDER BY v.valid_to ASC, v.id ASC
            """.trimIndent(),
            { row, _ -> toVoucher(row).toResponse() },
            actor.id,
            actor.id,
        )
    }

    @Transactional(readOnly = true)
    fun calculate(
        actor: AuthUser,
        code: String?,
        subtotal: Long,
        paymentMethod: PaymentMethod? = null,
        voucherId: Long? = null,
    ): AppliedVoucher? {
        requireCustomer(actor)

        if (subtotal < 0) {
            throw BadRequestException(message = "Giá chuyến không hợp lệ")
        }

        if (!code.isNullOrBlank() && voucherId != null) {
            throw BadRequestException(
                ErrorCode.VOUCHER_INVALID,
                "Chỉ được chọn mã voucher hoặc voucher cá nhân",
            )
        }

        if (code.isNullOrBlank() && voucherId == null) return null

        val voucher = if (voucherId != null) {
            if (voucherId <= 0) {
                throw BadRequestException(ErrorCode.VOUCHER_INVALID)
            }
            loadPersonal(voucherId, actor.id)
        } else {
            loadPublic(code!!.trim().uppercase(), actor.id)
        } ?: throw BadRequestException(ErrorCode.VOUCHER_INVALID)

        if (subtotal < voucher.minimumOrderAmount) {
            throw BadRequestException(
                ErrorCode.VOUCHER_INVALID,
                "Đơn hàng chưa đạt giá trị tối thiểu của voucher",
            )
        }

        if (
            voucher.allowedPaymentMethod != null &&
            voucher.allowedPaymentMethod != paymentMethod?.name
        ) {
            throw BadRequestException(
                ErrorCode.VOUCHER_INVALID,
                "Voucher không áp dụng cho phương thức thanh toán này",
            )
        }

        val rawDiscount = when (voucher.discountType) {
            "PERCENT" -> BigDecimal.valueOf(subtotal)
                .multiply(BigDecimal.valueOf(voucher.value))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN)
                .longValueExact()

            "FIXED" -> voucher.value

            else -> throw BadRequestException(
                ErrorCode.VOUCHER_INVALID,
                "Loại voucher không được hỗ trợ",
            )
        }

        val capped = voucher.maxDiscount?.let { minOf(rawDiscount, it) } ?: rawDiscount

        return AppliedVoucher(
            voucherId = voucher.id,
            code = voucher.code,
            discountAmount = minOf(subtotal, capped).coerceAtLeast(0),
        )
    }

    @Transactional
    fun consume(
        customerId: Long,
        applied: AppliedVoucher,
        rideId: Long,
        paymentMethod: PaymentMethod? = null,
    ) {
        val personalReward = applied.code == null

        val changed = jdbcTemplate.update(
            """
            UPDATE vouchers
            SET usage_count = usage_count + 1
            WHERE id = ?
              AND active = TRUE
              AND valid_from <= CURRENT_TIMESTAMP
              AND valid_to > CURRENT_TIMESTAMP
              AND (usage_limit IS NULL OR usage_count < usage_limit)
              AND (
                  (
                      ? = TRUE
                      AND source = 'SYSTEM_REWARD'
                      AND owner_customer_id = ?
                      AND code IS NULL
                  )
                  OR
                  (
                      ? = FALSE
                      AND source = 'PUBLIC_CODE'
                      AND owner_customer_id IS NULL
                      AND code IS NOT NULL
                  )
              )
              AND (
                  allowed_payment_method IS NULL
                  OR allowed_payment_method = ?
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM voucher_usages vu
                  WHERE vu.voucher_id = vouchers.id
                    AND vu.customer_id = ?
              )
            """.trimIndent(),
            applied.voucherId,
            personalReward,
            customerId,
            personalReward,
            paymentMethod?.name,
            customerId,
        )

        if (changed != 1) {
            throw BadRequestException(ErrorCode.VOUCHER_INVALID)
        }

        try {
            jdbcTemplate.update(
                """
                INSERT INTO voucher_usages (voucher_id, customer_id, ride_id)
                VALUES (?, ?, ?)
                """.trimIndent(),
                applied.voucherId,
                customerId,
                rideId,
            )
        } catch (_: DataIntegrityViolationException) {
            throw BadRequestException(ErrorCode.VOUCHER_INVALID)
        }
    }

    @Transactional
    fun releaseForRide(rideId: Long) {
        if (rideId <= 0) return

        val voucherId = jdbcTemplate.query(
            "SELECT voucher_id FROM voucher_usages WHERE ride_id = ?",
            { row, _ -> row.getLong(1) },
            rideId,
        ).firstOrNull() ?: return

        val deleted = jdbcTemplate.update(
            "DELETE FROM voucher_usages WHERE ride_id = ? AND voucher_id = ?",
            rideId,
            voucherId,
        )

        if (deleted == 1) {
            jdbcTemplate.update(
                """
                UPDATE vouchers
                SET usage_count = GREATEST(usage_count - 1, 0)
                WHERE id = ?
                """.trimIndent(),
                voucherId,
            )
        }
    }

    private fun loadPublic(code: String, customerId: Long): VoucherRow? =
        jdbcTemplate.query(
            """
            SELECT v.id, v.code, v.discount_type, v.value, v.max_discount,
                   v.minimum_order_amount, v.usage_count, v.usage_limit,
                   v.valid_from, v.valid_to, v.owner_customer_id,
                   v.source, v.allowed_payment_method
            FROM vouchers v
            WHERE UPPER(v.code) = ?
              AND v.source = 'PUBLIC_CODE'
              AND v.owner_customer_id IS NULL
              AND v.active = TRUE
              AND v.valid_from <= CURRENT_TIMESTAMP
              AND v.valid_to > CURRENT_TIMESTAMP
              AND (v.usage_limit IS NULL OR v.usage_count < v.usage_limit)
              AND NOT EXISTS (
                  SELECT 1
                  FROM voucher_usages vu
                  WHERE vu.voucher_id = v.id
                    AND vu.customer_id = ?
              )
            """.trimIndent(),
            { row, _ -> toVoucher(row) },
            code,
            customerId,
        ).firstOrNull()

    private fun loadPersonal(voucherId: Long, customerId: Long): VoucherRow? =
        jdbcTemplate.query(
            """
            SELECT v.id, v.code, v.discount_type, v.value, v.max_discount,
                   v.minimum_order_amount, v.usage_count, v.usage_limit,
                   v.valid_from, v.valid_to, v.owner_customer_id,
                   v.source, v.allowed_payment_method
            FROM vouchers v
            WHERE v.id = ?
              AND v.source = 'SYSTEM_REWARD'
              AND v.owner_customer_id = ?
              AND v.code IS NULL
              AND v.active = TRUE
              AND v.valid_from <= CURRENT_TIMESTAMP
              AND v.valid_to > CURRENT_TIMESTAMP
              AND (v.usage_limit IS NULL OR v.usage_count < v.usage_limit)
              AND NOT EXISTS (
                  SELECT 1
                  FROM voucher_usages vu
                  WHERE vu.voucher_id = v.id
                    AND vu.customer_id = ?
              )
            """.trimIndent(),
            { row, _ -> toVoucher(row) },
            voucherId,
            customerId,
            customerId,
        ).firstOrNull()

    private fun toVoucher(row: ResultSet) = VoucherRow(
        id = row.getLong("id"),
        code = row.getString("code"),
        discountType = row.getString("discount_type"),
        value = row.getLong("value"),
        maxDiscount = row.getObject("max_discount", Long::class.javaObjectType),
        minimumOrderAmount = row.getLong("minimum_order_amount"),
        usageCount = row.getInt("usage_count"),
        usageLimit = row.getObject("usage_limit", Int::class.javaObjectType),
        validFrom = row.getTimestamp("valid_from").toInstant(),
        validTo = row.getTimestamp("valid_to").toInstant(),
        ownerCustomerId = row.getObject("owner_customer_id", Long::class.javaObjectType),
        source = row.getString("source"),
        allowedPaymentMethod = row.getString("allowed_payment_method"),
    )

    private fun requireCustomer(actor: AuthUser) {
        if (actor.role != Role.CUSTOMER) {
            throw ForbiddenException(message = "Chỉ khách hàng được sử dụng voucher")
        }
    }

    private data class VoucherRow(
        val id: Long,
        val code: String?,
        val discountType: String,
        val value: Long,
        val maxDiscount: Long?,
        val minimumOrderAmount: Long,
        val usageCount: Int,
        val usageLimit: Int?,
        val validFrom: Instant,
        val validTo: Instant,
        val ownerCustomerId: Long?,
        val source: String,
        val allowedPaymentMethod: String?,
    ) {
        fun toResponse() = VoucherResponse(
            id = id,
            code = code,
            discountType = discountType,
            value = value,
            maxDiscount = maxDiscount,
            minimumOrderAmount = minimumOrderAmount,
            usageCount = usageCount,
            usageLimit = usageLimit,
            validFrom = validFrom,
            validTo = validTo,
            source = source,
        )
    }
}