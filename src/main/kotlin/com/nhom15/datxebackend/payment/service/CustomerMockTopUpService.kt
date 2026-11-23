package com.nhom15.datxebackend.payment.service

import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.payment.entity.*
import com.nhom15.datxebackend.payment.exception.*
import com.nhom15.datxebackend.common.exception.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CustomerMockTopUpService(private val wallets: WalletService, private val customers: CustomerRepository, private val jdbc: JdbcTemplate) {
    /** Self-service: chủ ví luôn là khách hàng đang đăng nhập. */
    @Transactional
    fun topUp(customerId: Long, amount: Long, key: String) = topUpForOwner(customerId, amount, key)

    /** Dùng cho endpoint admin (dev/test/demo): chủ ví được truyền rõ ràng. */
    @Transactional
    fun topUpForOwner(ownerId: Long, amount: Long, key: String) {
        if (amount !in 1..50_000_000L) throw InvalidTopUpAmountException()
        if (key.isBlank() || key.length > 100) throw BadRequestException(message = "Idempotency-Key không hợp lệ")
        customers.findByIdForUpdate(ownerId).orElseThrow { UserNotFoundException() }
        val prior = jdbc.query("SELECT amount FROM customer_mock_topups WHERE customer_id=? AND request_key=?",
            { row, _ -> row.getLong(1) }, ownerId, key).firstOrNull()
        if (prior != null) {
            if (prior != amount) throw ConflictException(message = "Idempotency-Key đã dùng cho số tiền khác")
            return
        }
        val entry = wallets.topUp(ownerId, WalletOwner.CUSTOMER, WalletType.CUSTOMER, amount)
        jdbc.update("INSERT INTO customer_mock_topups(customer_id,request_key,amount,transaction_id) VALUES (?,?,?,?)",
            ownerId, key, amount, entry.id)
    }
}
