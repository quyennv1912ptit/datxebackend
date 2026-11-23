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
import com.nhom15.datxebackend.payment.exception.*
import com.nhom15.datxebackend.payment.dto.DriverDailySummary
import com.nhom15.datxebackend.payment.dto.WalletResponse
import com.nhom15.datxebackend.payment.dto.WalletTransactionResponse
import com.nhom15.datxebackend.ride.event.RideCompleted
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.voucher.service.PromoBudgetService
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
    private val wallets: WalletService,
    private val events: org.springframework.context.ApplicationEventPublisher,
    private val promoBudget: PromoBudgetService? = null,
) {
    @Transactional(readOnly = true)
    fun getDriverDailySummary(driverId: Long): DriverDailySummary {
        val driver = driverRepository.findById(driverId).orElseThrow {
            NotFoundException(ErrorCode.USER_NOT_FOUND)
        }
        val zone = java.time.ZoneId.of("Asia/Ho_Chi_Minh")
        val date = java.time.LocalDate.now(zone)
        val start = java.sql.Timestamp.from(date.atStartOfDay(zone).toInstant())
        val end = java.sql.Timestamp.from(date.plusDays(1).atStartOfDay(zone).toInstant())
        val trips = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM rides WHERE driver_id = ? AND status = 'COMPLETED' AND completed_at >= ? AND completed_at < ?",
            Long::class.javaObjectType, driverId, start, end,
        ) ?: 0L
        val income = jdbcTemplate.queryForObject(
            """SELECT COALESCE(SUM(
                   CASE
                       WHEN p.method = 'WALLET' THEN COALESCE(wt.earning, 0)
                       WHEN p.method = 'CASH' THEN p.amount + COALESCE(wt.commission, 0) + COALESCE(wt.earning, 0)
                       ELSE 0
                   END
               ), 0)
               FROM payments p
               LEFT JOIN LATERAL (
                   SELECT
                       SUM(CASE WHEN w.type = 'COMMISSION_FEE' AND w.wallet_type = 'CREDIT' THEN w.amount ELSE 0 END) AS commission,
                       SUM(CASE WHEN w.type = 'TRIP_EARNING' AND w.wallet_type = 'CASH' THEN w.amount ELSE 0 END) AS earning
                   FROM wallet_transactions w
                   WHERE w.ride_id = p.ride_id AND w.driver_id = p.driver_id AND w.status = 'SUCCESS'
               ) wt ON TRUE
               WHERE p.driver_id = ? AND p.status = 'PAID' AND p.paid_at >= ? AND p.paid_at < ?""".trimIndent(),
            Long::class.javaObjectType, driverId, start, end,
        ) ?: 0L
        return DriverDailySummary(date, driver.fullName, driver.avatarUrl, trips, income)
    }

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
        val ride = rideRepository.findByIdForUpdate(rideId).orElseThrow { NotFoundException(ErrorCode.RIDE_NOT_FOUND) }
        if (ride.driverId != actor.id) throw NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        if (ride.status.name != "COMPLETED") {
            throw ConflictException(ErrorCode.INVALID_RIDE_STATE, "Chỉ xác nhận tiền mặt sau khi chuyến hoàn tất")
        }

        val payment = loadPayment(rideId, true) ?: throw NotFoundException(ErrorCode.PAYMENT_NOT_FOUND)
        if (payment.method != PaymentMethod.CASH.name) {
            throw PaymentMethodMismatchException()
        }
        if (payment.status != PaymentStatus.PAID.name) {
            val paidAt = java.sql.Timestamp.from(Instant.now())
            val changed = jdbcTemplate.update(
                "UPDATE payments SET status = ?, paid_at = ?, updated_at = ? WHERE ride_id = ? AND status = ? AND method = 'CASH'",
                PaymentStatus.PAID.name,
                paidAt,
                paidAt,
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
        if (payment.status != PaymentStatus.PAID.name) events.publishEvent(com.nhom15.datxebackend.payment.event.PaymentUpdated(
            rideId, ride.customerId, actor.id, PaymentMethod.CASH, PaymentMethod.CASH, PaymentStatus.PAID.name, payment.amount))
        return (loadPayment(rideId) ?: throw NotFoundException(ErrorCode.PAYMENT_NOT_FOUND)).toConfirmation(rideId)
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    @Transactional(propagation = Propagation.MANDATORY)
    fun onRideCompleted(event: RideCompleted) {
        val ride = rideRepository.findByIdForUpdate(event.rideId).orElseThrow {
            NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        }
        if (ride.status.name != "COMPLETED" || ride.customerId != event.customerId || ride.driverId != event.driverId
            || ride.finalFare != event.finalFare.amount) throw InvalidTripStateException("Dữ liệu quyết toán không khớp chuyến")
        if (ride.paymentMethod !in setOf(PaymentMethod.CASH, PaymentMethod.WALLET))
            throw PaymentMethodMismatchException()
        // settle() có thể đổi paymentMethod trên entity: giữ lại phương thức khách yêu cầu để báo event.
        val requestedMethod = ride.paymentMethod
        val acquired = jdbcTemplate.update(
            "INSERT INTO ride_wallet_settlements(ride_id) VALUES (?) ON CONFLICT DO NOTHING", ride.id)
        if (acquired == 0) return

        promoBudget?.spend(ride.id)

        // Driver's 80% share is based on the original fare before the voucher.
        val rawFare = ride.fareEstimate
        val settledMethod = wallets.settle(ride.id, ride.customerId, event.driverId, event.finalFare.amount, ride.paymentMethod, rawFare)
        if (requestedMethod != settledMethod) ride.paymentMethod = settledMethod
        val paidNow = settledMethod == PaymentMethod.WALLET || event.finalFare.amount == 0L
        val paidAt = if (paidNow) java.sql.Timestamp.from(Instant.now()) else null
        jdbcTemplate.update(
            """INSERT INTO payments (ride_id, customer_id, driver_id, amount, method, status, paid_at)
               VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (ride_id) DO UPDATE
               SET status = EXCLUDED.status, method = EXCLUDED.method, paid_at = EXCLUDED.paid_at, updated_at = CURRENT_TIMESTAMP""".trimIndent(),
            event.rideId, event.customerId, event.driverId, event.finalFare.amount, settledMethod.name,
            if (paidNow) PaymentStatus.PAID.name else PaymentStatus.PENDING.name,
            paidAt,
        )
        // ride_wallet_settlements ở trên là guard idempotency: event cũng chỉ phát một lần cho mỗi chuyến.
        events.publishEvent(com.nhom15.datxebackend.payment.event.PaymentUpdated(
            event.rideId, event.customerId, event.driverId, requestedMethod, settledMethod,
            if (paidNow) PaymentStatus.PAID.name else PaymentStatus.PENDING.name, event.finalFare.amount,
        ))
    }

    @Transactional(readOnly = true)
    fun getRidePayment(rideId: Long): com.nhom15.datxebackend.payment.dto.RidePaymentResponse? = loadPayment(rideId)?.let {
        com.nhom15.datxebackend.payment.dto.RidePaymentResponse(it.amount, it.method, it.status, it.paidAt)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun lockRidePayment(rideId: Long): com.nhom15.datxebackend.payment.dto.RidePaymentResponse? = loadPayment(rideId, true)?.let {
        com.nhom15.datxebackend.payment.dto.RidePaymentResponse(it.amount, it.method, it.status, it.paidAt)
    }

    @Transactional(readOnly = true)
    fun getDriverRideEarnings(driverId: Long, rideId: Long): com.nhom15.datxebackend.payment.dto.DriverRideEarnings? {
        val payment = loadPayment(rideId) ?: return null
        val cash = payment.method == PaymentMethod.CASH.name
        if (!cash) {
            val earning = jdbcTemplate.query(
                "SELECT COALESCE(SUM(amount), 0) AS total, COUNT(*) AS cnt FROM wallet_transactions WHERE driver_id=? AND ride_id=? AND type='TRIP_EARNING' AND status='SUCCESS'",
                { row, _ -> row.getLong("total") to row.getLong("cnt") }, driverId, rideId).firstOrNull()
            if (earning == null || earning.second == 0L) return null
            val recorded = earning.first
            return com.nhom15.datxebackend.payment.dto.DriverRideEarnings(
                recorded, (payment.amount - recorded).coerceAtLeast(0), true, payment.status == PaymentStatus.PAID.name)
        }
        val commissionRecorded = jdbcTemplate.query(
            "SELECT amount FROM wallet_transactions WHERE driver_id=? AND ride_id=? AND type='COMMISSION_FEE' AND status='SUCCESS'",
            { row, _ -> row.getLong("amount") }, driverId, rideId).firstOrNull()
        val subsidyRecorded = jdbcTemplate.query(
            "SELECT amount FROM wallet_transactions WHERE driver_id=? AND ride_id=? AND type='TRIP_EARNING' AND wallet_type='CASH' AND status='SUCCESS'",
            { row, _ -> row.getLong("amount") }, driverId, rideId).firstOrNull()
        if (commissionRecorded == null && subsidyRecorded == null) {
            val settled = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM ride_wallet_settlements WHERE ride_id = ?)",
                Boolean::class.java,
                rideId,
            ) == true
            if (!settled) return null
        }
        val commission = commissionRecorded ?: 0L
        val subsidy = subsidyRecorded ?: 0L
        return com.nhom15.datxebackend.payment.dto.DriverRideEarnings(
            payment.amount + commission + subsidy,
            -commission,
            subsidy > 0L,
            payment.status == PaymentStatus.PAID.name || payment.amount == 0L,
        )
    }

    private fun loadPayment(rideId: Long, forUpdate: Boolean = false): PaymentRow? = jdbcTemplate.query(
        "SELECT amount, method, status, paid_at FROM payments WHERE ride_id = ?" + if (forUpdate) " FOR UPDATE" else "",
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
