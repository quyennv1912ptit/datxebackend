package com.nhom15.datxebackend.payment.service

import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.PaymentStatus
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.payment.dto.WalletResponse
import com.nhom15.datxebackend.payment.dto.WalletTransactionResponse
import com.nhom15.datxebackend.ride.event.RideCompleted
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.payment.dto.CashPaymentConfirmationResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant

@Service
class PaymentService(
    private val jdbcTemplate: JdbcTemplate,
    private val driverRepository: DriverRepository,
    private val rideRepository: RideRepository,
) {
    @Transactional(readOnly = true)
    fun getDriverWallet(driverId: Long): WalletResponse {
        val driver = driverRepository.findById(driverId).orElseThrow {
            NotFoundException(ErrorCode.USER_NOT_FOUND)
        }
        val transactions = jdbcTemplate.query(
            """SELECT id, ride_id, type, amount, balance_after, description, created_at
               FROM wallet_transactions WHERE driver_id = ? ORDER BY created_at DESC, id DESC LIMIT 100""".trimIndent(),
            { row, _ ->
                WalletTransactionResponse(
                    id = row.getLong("id"), rideId = row.getObject("ride_id", Long::class.javaObjectType),
                    type = row.getString("type"), amount = row.getLong("amount"),
                    balanceAfter = row.getObject("balance_after", Long::class.javaObjectType), description = row.getString("description"),
                    createdAt = row.getTimestamp("created_at").toInstant(),
                )
            },
            driverId,
        )
        return WalletResponse(driver.incomeBalance ?: 0L, transactions)
    }

    @Transactional
    fun confirmCashReceived(actor: AuthUser, rideId: Long): CashPaymentConfirmationResponse {
        if (actor.role != Role.DRIVER) throw ForbiddenException(message = "Chỉ tài xế được xác nhận tiền mặt")
        if (rideId <= 0) throw BadRequestException(message = "rideId phải lớn hơn 0")
        val ride = rideRepository.findByIdAndDriverId(rideId, actor.id)
            ?: throw NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        if (ride.status.name != "COMPLETED") {
            throw ConflictException(ErrorCode.INVALID_RIDE_STATE, "Chỉ xác nhận tiền mặt sau khi chuyến hoàn tất")
        }

        val payment = loadPayment(rideId) ?: throw NotFoundException(ErrorCode.PAYMENT_NOT_FOUND)
        if (payment.method != PaymentMethod.CASH.name) {
            throw BadRequestException(message = "Chuyến này không dùng phương thức thanh toán tiền mặt")
        }
        if (payment.status != PaymentStatus.PAID.name) {
            val changed = jdbcTemplate.update(
                "UPDATE payments SET status = ?, paid_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE ride_id = ? AND status = ?",
                PaymentStatus.PAID.name,
                rideId,
                PaymentStatus.PENDING.name,
            )
            if (changed == 0) {
                val latest = loadPayment(rideId) ?: throw NotFoundException(ErrorCode.PAYMENT_NOT_FOUND)
                if (latest.status != PaymentStatus.PAID.name) {
                    throw ConflictException(message = "Trạng thái thanh toán vừa thay đổi; hãy tải lại chuyến")
                }
                return latest.toConfirmation(rideId)
            }
        }
        return (loadPayment(rideId) ?: throw NotFoundException(ErrorCode.PAYMENT_NOT_FOUND)).toConfirmation(rideId)
    }

    /** Persist the payment row in the ride-completion transaction so it cannot be lost after commit. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    @Transactional(propagation = Propagation.MANDATORY)
    fun onRideCompleted(event: RideCompleted) {
        val ride = rideRepository.findById(event.rideId).orElse(null) ?: return
        jdbcTemplate.update(
            """INSERT INTO payments (ride_id, customer_id, driver_id, amount, method, status)
               VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (ride_id) DO NOTHING""".trimIndent(),
            event.rideId, event.customerId, event.driverId, event.finalFare.amount, ride.paymentMethod.name,
            PaymentStatus.PENDING.name,
        )
    }

    private fun loadPayment(rideId: Long): PaymentRow? = jdbcTemplate.query(
        "SELECT amount, method, status, paid_at FROM payments WHERE ride_id = ?",
        { row, _ ->
            PaymentRow(
                amount = row.getLong("amount"),
                method = row.getString("method"),
                status = row.getString("status"),
                paidAt = row.getTimestamp("paid_at")?.toInstant(),
            )
        },
        rideId,
    ).firstOrNull()

    private data class PaymentRow(val amount: Long, val method: String, val status: String, val paidAt: Instant?) {
        fun toConfirmation(rideId: Long) = CashPaymentConfirmationResponse(rideId, amount, status, paidAt)
    }
}
