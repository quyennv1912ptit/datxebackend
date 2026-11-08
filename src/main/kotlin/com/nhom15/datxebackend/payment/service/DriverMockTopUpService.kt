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
    @Transactional
    fun topUp(driverId: Long, amount: Long, key: String) {
        if (amount !in 1..50_000_000L) throw InvalidTopUpAmountException()
        if (key.isBlank() || key.length > 100) throw BadRequestException(message = "Idempotency-Key không hợp lệ")
        drivers.findByIdForUpdate(driverId).orElseThrow { UserNotFoundException() }
        val prior = jdbc.query("SELECT amount FROM driver_mock_topups WHERE driver_id=? AND request_key=?",
            { row, _ -> row.getLong(1) }, driverId, key).firstOrNull()
        if (prior != null) {
            if (prior != amount) throw ConflictException(message = "Idempotency-Key đã dùng cho số tiền khác")
            return
        }
        val entry = wallets.topUp(driverId, WalletOwner.DRIVER, WalletType.CREDIT, amount)
        jdbc.update("INSERT INTO driver_mock_topups(driver_id,request_key,amount,transaction_id) VALUES (?,?,?,?)",
            driverId, key, amount, entry.id)
    }
}
