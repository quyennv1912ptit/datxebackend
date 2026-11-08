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
    @Transactional
    fun topUp(customerId: Long, amount: Long, key: String) {
        if (amount !in 1..50_000_000L) throw InvalidTopUpAmountException()
        if (key.isBlank() || key.length > 100) throw BadRequestException(message = "Idempotency-Key không hợp lệ")
        customers.findByIdForUpdate(customerId).orElseThrow { UserNotFoundException() }
        val prior = jdbc.query("SELECT amount FROM customer_mock_topups WHERE customer_id=? AND request_key=?",
            { row, _ -> row.getLong(1) }, customerId, key).firstOrNull()
        if (prior != null) {
            if (prior != amount) throw ConflictException(message = "Idempotency-Key đã dùng cho số tiền khác")
            return
        }
        val entry = wallets.topUp(customerId, WalletOwner.CUSTOMER, WalletType.CUSTOMER, amount)
        jdbc.update("INSERT INTO customer_mock_topups(customer_id,request_key,amount,transaction_id) VALUES (?,?,?,?)",
            customerId, key, amount, entry.id)
    }
}
