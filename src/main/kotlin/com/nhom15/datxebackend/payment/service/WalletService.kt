package com.nhom15.datxebackend.payment.service

import com.nhom15.datxebackend.common.exception.*
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.entity.*
import com.nhom15.datxebackend.payment.dto.*
import com.nhom15.datxebackend.payment.exception.*
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.payment.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.beans.factory.annotation.Value

@Service
class WalletService(
    private val wallets: WalletRepository,
    private val transactions: WalletTransactionRepository,
    private val drivers: DriverRepository,
    private val customers: CustomerRepository,
    @Value("\${wallet.minimum-credit:50000}") private val minimumCredit: Long,
) {
    init { require(minimumCredit >= 0) { "wallet.minimum-credit must be nonnegative" } }

    @Transactional
    fun balances(userId: Long, owner: WalletOwner): List<WalletBalanceDto> =
        (if (owner == WalletOwner.DRIVER) listOf(WalletType.CREDIT, WalletType.CASH) else listOf(WalletType.CUSTOMER))
            .map { locked(userId, owner, it) }.map { WalletBalanceDto(it.walletType, it.balance) }

    @Transactional(readOnly = true)
    fun history(userId: Long, owner: WalletOwner, page: Int, walletType: WalletType? = null) = transactions.history(userId, owner.name, walletType?.name,
        org.springframework.data.domain.PageRequest.of(page.coerceAtLeast(0), 20)).map { it.toDto() }

    // Wallets are provisioned at account creation by the V19 database triggers.
    private fun locked(userId: Long, owner: WalletOwner, type: WalletType): Wallet {
        if ((owner == WalletOwner.CUSTOMER) != (type == WalletType.CUSTOMER)) throw InvalidWalletTypeException()
        if (owner == WalletOwner.DRIVER) drivers.findByIdForUpdate(userId).orElseThrow { UserNotFoundException() }
        else customers.findByIdForUpdate(userId).orElseThrow { UserNotFoundException() }
        return wallets.lockWallet(userId, owner, type) ?: throw WalletNotFoundException()
    }

    private fun change(wallet: Wallet, delta: Long, type: TransactionType, rideId: Long? = null,
                       withdrawalId: Long? = null, status: WalletStatus = WalletStatus.SUCCESS, description: String? = null): WalletTransaction {
        val next = try { Math.addExact(wallet.balance, delta) } catch (_: ArithmeticException) {
            throw InvalidAmountException("Số tiền vượt giới hạn")
        }
        val creditDebt = wallet.walletType == WalletType.CREDIT &&
            (type == TransactionType.COMMISSION_FEE || (type in setOf(TransactionType.TOP_UP, TransactionType.REFUND) && wallet.balance < 0 && delta > 0))
        if (next < 0 && !creditDebt) throw when (wallet.walletType) {
            WalletType.CUSTOMER -> InsufficientCustomerBalanceException()
            WalletType.CASH -> InsufficientCashBalanceException()
            WalletType.CREDIT -> InsufficientCreditBalanceException()
        }
        wallet.balance = next
        if (wallet.userType == WalletOwner.DRIVER) {
            val driver = drivers.findByIdForUpdate(wallet.userId).orElseThrow { UserNotFoundException() }
            if (wallet.walletType == WalletType.CREDIT) {
                driver.creditBalance = next
                if (next < 0) driver.status = DriverStatus.BLOCKED
                else if (type in setOf(TransactionType.TOP_UP, TransactionType.REFUND) && driver.status == DriverStatus.BLOCKED && next >= minimumCredit)
                    driver.status = DriverStatus.OFFLINE
            } else driver.incomeBalance = next
        } else customers.findByIdForUpdate(wallet.userId).orElseThrow { UserNotFoundException() }.walletBalance = next
        val legacyType = when (type) {
            TransactionType.TOP_UP -> "DEPOSIT"
            TransactionType.RIDE_PAYMENT -> if (delta < 0) "TRIP_PAYMENT" else "TRIP_EARNING"
            else -> type.name
        }

        return transactions.save(WalletTransaction(
            driverId = wallet.userId.takeIf { wallet.userType == WalletOwner.DRIVER },
            customerId = wallet.userId.takeIf { wallet.userType == WalletOwner.CUSTOMER },
            walletType = wallet.walletType.name, amount = delta, legacyType = legacyType, transactionType = type,
            status = status, rideId = rideId, balanceAfter = next, withdrawalId = withdrawalId, description = description,
        ))
    }

    @Transactional
    fun creditStatus(driverId: Long): DriverCreditStatus {
        val wallet = locked(driverId, WalletOwner.DRIVER, WalletType.CREDIT)
        val driver = drivers.findByIdForUpdate(driverId).orElseThrow { UserNotFoundException() }
        val blocked = driver.status == DriverStatus.BLOCKED
        val shortfall = java.math.BigInteger.valueOf(minimumCredit).subtract(java.math.BigInteger.valueOf(wallet.balance))
            .max(java.math.BigInteger.ZERO).min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).toLong()
        return DriverCreditStatus(wallet.balance, minimumCredit, shortfall, !blocked && wallet.balance >= minimumCredit, blocked)
    }

    @Transactional
    fun checkBooking(customerId: Long, fare: Long) {
        requireFare(fare)
        if (locked(customerId, WalletOwner.CUSTOMER, WalletType.CUSTOMER).balance < fare) throw InsufficientCustomerBalanceException()
    }

    @Transactional
    fun requireEligibleDriver(driverId: Long, cashFare: Long? = null) {
        val balance = locked(driverId, WalletOwner.DRIVER, WalletType.CREDIT).balance
        if (balance < minimumCredit) throw CreditWalletBelowMinimumException()
        if (cashFare != null && balance < commission(cashFare)) throw InsufficientCreditBalanceException()

    }

    /**
     * Quyết toán chuyến và trả về phương thức thực tế đã dùng.
     *
     * WALLET chỉ được thu khi ví khách đủ tiền tại thời điểm quyết toán. Nếu thiếu,
     * chuyển ngay sang CASH (trừ chiết khấu vào CREDIT như cuốc tiền mặt bình thường)
     * thay vì ném lỗi làm rollback cả bước hoàn tất chuyến.
     */
    @Transactional
    fun settle(rideId: Long, customerId: Long, driverId: Long, fare: Long, method: PaymentMethod): PaymentMethod {
        requireFare(fare)
        // Caller holds the ride lock; unique settlement marker is acquired by PaymentService.
        val fee = commission(fare)
        return when (method) {
            PaymentMethod.CASH -> {
                change(locked(driverId, WalletOwner.DRIVER, WalletType.CREDIT), -fee, TransactionType.COMMISSION_FEE, rideId)
                PaymentMethod.CASH
            }

            PaymentMethod.WALLET -> {
                val customer = locked(customerId, WalletOwner.CUSTOMER, WalletType.CUSTOMER)
                if (customer.balance < fare) {
                    // Không đủ tiền: quyết toán như CASH để tài xế vẫn hoàn tất được chuyến.
                    change(
                        locked(driverId, WalletOwner.DRIVER, WalletType.CREDIT), -fee, TransactionType.COMMISSION_FEE, rideId,
                        description = "Chiết khấu chuyến (ví khách không đủ, tự động thu tiền mặt)",
                    )
                    PaymentMethod.CASH
                } else {
                    val driver = locked(driverId, WalletOwner.DRIVER, WalletType.CASH)
                    change(customer, -fare, TransactionType.RIDE_PAYMENT, rideId)
                    change(driver, fare - fee, TransactionType.RIDE_PAYMENT, rideId)
                    PaymentMethod.WALLET
                }
            }

            else -> throw PaymentMethodMismatchException()
        }
    }

    @Transactional
    fun pendingCashToWallet(rideId: Long, customerId: Long, driverId: Long, fare: Long) {
        requireFare(fare)
        val customer = locked(customerId, WalletOwner.CUSTOMER, WalletType.CUSTOMER)
        val credit = locked(driverId, WalletOwner.DRIVER, WalletType.CREDIT)
        val cash = locked(driverId, WalletOwner.DRIVER, WalletType.CASH)
        val commission = transactions.findByDriverIdAndRideIdAndLegacyTypeAndStatus(
            driverId, rideId, "COMMISSION_FEE", WalletStatus.SUCCESS)
            ?: throw ConflictException(message = "Chưa có giao dịch chiết khấu để chuyển thanh toán")
        if (commission.amount > 0 || commission.amount < -fare || commission.walletType != "CREDIT")
            throw ConflictException(message = "Dữ liệu chiết khấu không khớp chuyến")
        if (transactions.existsByRideIdAndTransactionType(rideId, TransactionType.RIDE_PAYMENT))
            throw ConflictException(message = "Chuyến đã có giao dịch thanh toán ví")
        val fee = -commission.amount
        change(customer, -fare, TransactionType.RIDE_PAYMENT, rideId)
        if (fee > 0) change(credit, fee, TransactionType.REFUND, rideId, description = "Hoàn chiết khấu khi khách đổi sang thanh toán ví")
        change(cash, fare - fee, TransactionType.RIDE_PAYMENT, rideId)
    }

    @Transactional
    fun pendingWalletToCash(rideId: Long, driverId: Long, fare: Long) {
        if (transactions.existsByRideIdAndTransactionType(rideId, TransactionType.RIDE_PAYMENT) ||
            transactions.findByDriverIdAndRideIdAndLegacyTypeAndStatus(driverId, rideId, "COMMISSION_FEE", WalletStatus.SUCCESS) != null)
            throw ConflictException(message = "Đã có biến động ví; không thể đổi sang tiền mặt")
        requireFare(fare)
        change(locked(driverId, WalletOwner.DRIVER, WalletType.CREDIT), -commission(fare), TransactionType.COMMISSION_FEE, rideId)
    }

    @Transactional
    fun topUp(userId: Long, owner: WalletOwner, type: WalletType, amount: Long): WalletTransaction {
        if (amount !in 1..50_000_000L) throw InvalidTopUpAmountException()
        return change(locked(userId, owner, type), amount, TransactionType.TOP_UP)
    }

    @Transactional
    fun hold(driverId: Long, amount: Long, withdrawalId: Long): WalletTransaction {
        requireAmount(amount)
        return change(locked(driverId, WalletOwner.DRIVER, WalletType.CASH), -amount,
            TransactionType.WITHDRAW, withdrawalId = withdrawalId, status = WalletStatus.PENDING)
    }

    @Transactional
    fun release(driverId: Long, amount: Long, withdrawalId: Long) {
        requireAmount(amount)
        change(locked(driverId, WalletOwner.DRIVER, WalletType.CASH), amount,
            TransactionType.REFUND, withdrawalId = withdrawalId)
    }

    fun requireAmount(amount: Long) { if (amount <= 0) throw InvalidAmountException() }
    private fun requireFare(fare: Long) { if (fare < 0) throw BadRequestException(message = "Cước phí không hợp lệ") }

    companion object {
        // Round down to whole VND; divide first to avoid multiplication overflow.
        fun commission(fare: Long): Long { require(fare >= 0); return fare / 5 }
    }
}
