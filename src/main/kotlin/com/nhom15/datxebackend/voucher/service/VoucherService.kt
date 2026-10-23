package com.nhom15.datxebackend.voucher.service

import com.nhom15.datxebackend.common.api.ErrorCode
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

    fun listAvailable(actor: AuthUser): List<VoucherResponse> {
        requireCustomer(actor)
        return jdbcTemplate.query(
            """SELECT v.id, v.code, v.discount_type, v.value, v.max_discount,
                      v.minimum_order_amount, v.usage_count, v.usage_limit, v.valid_from, v.valid_to
               FROM vouchers v
               WHERE v.active = TRUE
                 AND v.valid_from <= CURRENT_TIMESTAMP AND v.valid_to > CURRENT_TIMESTAMP
                 AND (v.usage_limit IS NULL OR v.usage_count < v.usage_limit)
                 AND NOT EXISTS (
                     SELECT 1 FROM voucher_usages vu
                     WHERE vu.voucher_id = v.id AND vu.customer_id = ?
                 )
               ORDER BY v.valid_to ASC, v.id ASC""".trimIndent(),
            ::toResponse,
            actor.id,
        )
    }

    fun calculate(actor: AuthUser, code: String?, subtotal: Long): AppliedVoucher? {
        requireCustomer(actor)
        if (code.isNullOrBlank()) return null
        if (subtotal < 0) throw BadRequestException(message = "Giá chuyến không hợp lệ")
        val voucher = load(code.trim().uppercase(), actor.id)
            ?: throw BadRequestException(ErrorCode.VOUCHER_INVALID)
        if (subtotal < voucher.minimumOrderAmount) throw BadRequestException(
            ErrorCode.VOUCHER_INVALID,
            "Đơn hàng chưa đạt giá trị tối thiểu của voucher",
        )
        val rawDiscount = when (voucher.discountType) {
            "PERCENT" -> BigDecimal.valueOf(subtotal)
                .multiply(BigDecimal.valueOf(voucher.value))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN)
                .longValueExact()
            "FIXED" -> voucher.value
            else -> throw BadRequestException(ErrorCode.VOUCHER_INVALID, "Loại voucher không được hỗ trợ")
        }
        val capped = voucher.maxDiscount?.let { minOf(rawDiscount, it) } ?: rawDiscount
        return AppliedVoucher(voucher.id, voucher.code, minOf(subtotal, capped).coerceAtLeast(0))
    }

    @Transactional
    fun consume(customerId: Long, applied: AppliedVoucher, rideId: Long) {
        val changed = jdbcTemplate.update(
            """UPDATE vouchers SET usage_count = usage_count + 1
               WHERE id = ? AND active = TRUE
                 AND valid_from <= CURRENT_TIMESTAMP AND valid_to > CURRENT_TIMESTAMP
                 AND (usage_limit IS NULL OR usage_count < usage_limit)""".trimIndent(),
            applied.voucherId,
        )
        if (changed != 1) throw BadRequestException(ErrorCode.VOUCHER_INVALID)
        try {
            jdbcTemplate.update(
                "INSERT INTO voucher_usages (voucher_id, customer_id, ride_id) VALUES (?, ?, ?)",
                applied.voucherId, customerId, rideId,
            )
        } catch (_: DataIntegrityViolationException) {
            throw BadRequestException(ErrorCode.VOUCHER_INVALID)
        }
    }

    private fun load(code: String, customerId: Long): VoucherRow? = jdbcTemplate.query(
        """SELECT v.id, v.code, v.discount_type, v.value, v.max_discount,
                  v.minimum_order_amount, v.usage_count, v.usage_limit, v.valid_from, v.valid_to
           FROM vouchers v
           WHERE UPPER(v.code) = ? AND v.active = TRUE
             AND v.valid_from <= CURRENT_TIMESTAMP AND v.valid_to > CURRENT_TIMESTAMP
             AND (v.usage_limit IS NULL OR v.usage_count < v.usage_limit)
             AND NOT EXISTS (
                 SELECT 1 FROM voucher_usages vu
             WHERE vu.voucher_id = v.id AND vu.customer_id = ?
             )""".trimIndent(),
        { row, _ -> toVoucher(row) },
        code,
            customerId,
    ).firstOrNull()

    private fun toResponse(row: ResultSet, rowNum: Int) = toVoucher(row).toResponse()

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
    )

    private fun requireCustomer(actor: AuthUser) {
        if (actor.role != Role.CUSTOMER) throw ForbiddenException(message = "Chỉ khách hàng được sử dụng voucher")
    }

    private data class VoucherRow(
        val id: Long,
        val code: String,
        val discountType: String,
        val value: Long,
        val maxDiscount: Long?,
        val minimumOrderAmount: Long,
        val usageCount: Int,
        val usageLimit: Int?,
        val validFrom: Instant,
        val validTo: Instant,
    ) {
        fun toResponse() = VoucherResponse(
            id, code, discountType, value, maxDiscount, minimumOrderAmount,
            usageCount, usageLimit, validFrom, validTo,
        )
    }
}
