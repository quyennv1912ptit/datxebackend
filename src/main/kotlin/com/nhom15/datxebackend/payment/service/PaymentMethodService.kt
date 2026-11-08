package com.nhom15.datxebackend.payment.service

import com.nhom15.datxebackend.common.enums.*
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.*
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.payment.dto.RidePaymentResponse
import com.nhom15.datxebackend.payment.event.PaymentUpdated
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PaymentMethodService(private val rides: RideRepository, private val payments: PaymentService,
    private val wallets: WalletService, private val jdbc: JdbcTemplate, private val events: ApplicationEventPublisher) {
    @Transactional
    fun change(actor: AuthUser, rideId: Long, method: PaymentMethod, expectedMethod: PaymentMethod, key: String): RidePaymentResponse {
        if (actor.role != Role.CUSTOMER) throw ForbiddenException()
        if (key.isBlank() || key.length > 100) throw BadRequestException(message = "Idempotency-Key không hợp lệ")
        if (method !in setOf(PaymentMethod.CASH, PaymentMethod.WALLET) || expectedMethod !in setOf(PaymentMethod.CASH, PaymentMethod.WALLET))
            throw BadRequestException(message = "Chỉ hỗ trợ tiền mặt và ví ứng dụng")
        val ride = rides.findByIdForUpdate(rideId).orElseThrow { NotFoundException(ErrorCode.RIDE_NOT_FOUND) }
        if (ride.customerId != actor.id) throw NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        val payment = payments.lockRidePayment(rideId) ?: throw NotFoundException(ErrorCode.PAYMENT_NOT_FOUND)
        val prior = jdbc.query("SELECT from_method,to_method FROM payment_method_changes WHERE ride_id=? AND customer_id=? AND request_key=?",
            { row, _ -> row.getString("from_method") to row.getString("to_method") }, rideId, actor.id, key).firstOrNull()
        if (prior != null) {
            if (prior != (expectedMethod.name to method.name) || payment.method != method.name)
                throw ConflictException(message = "Yêu cầu hoặc phương thức thanh toán đã thay đổi")
            return payment
        }
        if (ride.status != RideStatus.COMPLETED || payment.status != PaymentStatus.PENDING.name)
            throw ConflictException(ErrorCode.PAYMENT_ALREADY_PAID, "Chỉ được đổi khi khoản thanh toán còn PENDING; vui lòng cập nhật hóa đơn")
        if (payment.method != expectedMethod.name || ride.paymentMethod.name != payment.method)
            throw ConflictException(message = "Phương thức thanh toán đã thay đổi. Vui lòng kiểm tra lại.")
        if (method == expectedMethod) throw BadRequestException(message = "Hãy chọn phương thức khác phương thức hiện tại")
        val driverId = ride.driverId ?: throw ConflictException(message = "Chuyến chưa có tài xế")
        if (method == PaymentMethod.WALLET) wallets.pendingCashToWallet(rideId, actor.id, driverId, payment.amount)
        else {
            wallets.pendingWalletToCash(rideId, driverId, payment.amount)
            jdbc.update("INSERT INTO ride_wallet_settlements(ride_id) VALUES (?) ON CONFLICT DO NOTHING", rideId)
        }
        val status = if (method == PaymentMethod.WALLET) PaymentStatus.PAID.name else PaymentStatus.PENDING.name
        // paid_at/updated_at lấy từ đồng hồ JVM, đồng bộ với xác nhận tiền mặt và quyết toán.
        val changedAt = java.sql.Timestamp.from(java.time.Instant.now())
        jdbc.update("UPDATE payments SET method=?,status=?,paid_at=?,updated_at=? WHERE ride_id=?",
            method.name, status, if (status == PaymentStatus.PAID.name) changedAt else null, changedAt, rideId)
        ride.paymentMethod = method
        jdbc.update("INSERT INTO payment_method_changes(ride_id,customer_id,request_key,from_method,to_method,amount) VALUES(?,?,?,?,?,?)",
            rideId, actor.id, key, expectedMethod.name, method.name, payment.amount)
        events.publishEvent(PaymentUpdated(rideId, actor.id, driverId, expectedMethod, method, status, payment.amount))
        return payments.getRidePayment(rideId)!!
    }
}
