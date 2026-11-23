package com.nhom15.datxebackend.payment.service

import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.entity.*
import com.nhom15.datxebackend.payment.exception.*
import com.nhom15.datxebackend.common.exception.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DriverMockTopUpService(private val wallets: WalletService, private val drivers: DriverRepository, private val jdbc: JdbcTemplate) {
    /** Self-service: chủ ví luôn là tài xế đang đăng nhập, chỉ nạp CREDIT. */
    @Transactional
    fun topUp(driverId: Long, amount: Long, key: String) = topUpForOwner(driverId, amount, key, WalletType.CREDIT)

    /**
     * Dùng cho endpoint admin (dev/test/demo): chủ ví được truyền rõ ràng, kèm loại ví.
     * Receipt (driver_id, request_key) chống cộng lặp; loại ví được đối chiếu qua
     * wallet_transactions nên cùng một key không thể nạp sang ví khác.
     */
    @Transactional
    fun topUpForOwner(
        ownerId: Long,
        amount: Long,
        key: String,
        walletType: WalletType = WalletType.CREDIT,
    ) {
        if (walletType !in setOf(WalletType.CREDIT, WalletType.CASH)) {
            throw BadRequestException(message = "Tài xế chỉ hỗ trợ ví CREDIT hoặc CASH")
        }
        if (amount !in 1..50_000_000L) throw InvalidTopUpAmountException()
        if (key.isBlank() || key.length > 100) throw BadRequestException(message = "Idempotency-Key không hợp lệ")
        drivers.findByIdForUpdate(ownerId).orElseThrow { UserNotFoundException() }
        val prior = jdbc.query(
            """SELECT r.amount, t.wallet_type FROM driver_mock_topups r
               JOIN wallet_transactions t ON t.id = r.transaction_id
               WHERE r.driver_id = ? AND r.request_key = ?""".trimIndent(),
            { row, _ -> row.getLong("amount") to row.getString("wallet_type") }, ownerId, key,
        ).firstOrNull()
        if (prior != null) {
            if (prior.first != amount || prior.second != walletType.name) {
                throw ConflictException(message = "Idempotency-Key đã dùng cho số tiền hoặc ví khác")
            }
            return
        }
        val entry = wallets.topUp(ownerId, WalletOwner.DRIVER, walletType, amount)
        jdbc.update("INSERT INTO driver_mock_topups(driver_id,request_key,amount,transaction_id) VALUES (?,?,?,?)",
            ownerId, key, amount, entry.id)
    }
}
