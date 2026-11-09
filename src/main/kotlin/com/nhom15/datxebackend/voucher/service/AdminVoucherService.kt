package com.nhom15.datxebackend.voucher.service

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.voucher.dto.AdminVoucherCreatedResponse
import com.nhom15.datxebackend.voucher.dto.CreateAdminVoucherRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

@Service
class AdminVoucherService(
    private val jdbcTemplate: JdbcTemplate,
) {
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
                throw BadRequestException(
                    message = "Voucher công khai bắt buộc phải có mã",
                )
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
                throw BadRequestException(
                    message = "Voucher cá nhân không được có mã công khai",
                )
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
            throw BadRequestException(
                message = "discountType chỉ hỗ trợ PERCENT hoặc FIXED",
            )
        }

        if (request.value <= 0) {
            throw BadRequestException(message = "value phải lớn hơn 0")
        }

        if (discountType == "PERCENT" && request.value > 100) {
            throw BadRequestException(
                message = "Voucher phần trăm không được vượt quá 100",
            )
        }

        if (request.maxDiscount != null && request.maxDiscount <= 0) {
            throw BadRequestException(message = "maxDiscount phải lớn hơn 0")
        }

        if (request.minimumOrderAmount < 0) {
            throw BadRequestException(
                message = "minimumOrderAmount không được âm",
            )
        }

        if (request.usageLimit != null && request.usageLimit <= 0) {
            throw BadRequestException(message = "usageLimit phải lớn hơn 0")
        }

        if (request.validTo <= request.validFrom) {
            throw BadRequestException(message = "validTo phải sau validFrom")
        }

        val id = try {
            jdbcTemplate.query(
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
                    allowed_payment_method
                )
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, TRUE, ?, ?, 0, ?)
                RETURNING id
                """.trimIndent(),
                { row: ResultSet, _: Int -> row.getLong("id") },
                normalizedCode,
                discountType,
                request.value,
                request.maxDiscount,
                request.minimumOrderAmount,
                request.usageLimit,
                request.validFrom,
                request.validTo,
                ownerCustomerId,
                source,
                request.allowedPaymentMethod?.name,
            ).single()
        } catch (_: DataIntegrityViolationException) {
            throw BadRequestException(
                message = "Mã voucher đã tồn tại hoặc dữ liệu không hợp lệ",
            )
        }

        return AdminVoucherCreatedResponse(
            id = id,
            code = normalizedCode,
            source = source,
            ownerCustomerId = ownerCustomerId,
            discountType = discountType,
            value = request.value,
            maxDiscount = request.maxDiscount,
            minimumOrderAmount = request.minimumOrderAmount,
            usageLimit = request.usageLimit,
            validFrom = request.validFrom,
            validTo = request.validTo,
            allowedPaymentMethod = request.allowedPaymentMethod?.name,
        )
    }

    private fun requireAdmin(actor: AuthUser) {
        if (actor.role != Role.ADMIN) {
            throw ForbiddenException(message = "Chỉ quản trị viên được quản lý voucher")
        }
    }
}
