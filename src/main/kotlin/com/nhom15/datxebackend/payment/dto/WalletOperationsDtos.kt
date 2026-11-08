package com.nhom15.datxebackend.payment.dto

import com.nhom15.datxebackend.payment.entity.*
import java.time.Instant

data class WalletBalanceDto(val walletType: WalletType, val balance: Long)
data class WithdrawalDto(val id: Long, val driverId: Long, val amount: Long, val status: WithdrawalStatus,
                         val createdAt: Instant, val reviewedAt: Instant?, val reviewedBy: Long?, val rejectReason: String?)
fun WithdrawalRequest.toDto() = WithdrawalDto(id, driverId, amount, status, createdAt, reviewedAt, reviewedBy, rejectReason)

/** Hàng đợi rút tiền cho admin: kèm thông tin nhận tiền của tài xế để duyệt/chuyển khoản. */
data class AdminWithdrawalDto(
    val id: Long,
    val driverId: Long,
    val driverName: String?,
    val driverPhoneNumber: String?,
    val bankName: String?,
    val bankAccountNumber: String?,
    val amount: Long,
    val status: WithdrawalStatus,
    val createdAt: Instant,
    val reviewedAt: Instant?,
    val reviewedBy: Long?,
    val rejectReason: String?,
)
data class WalletTransactionDto(val transactionId: Long, val userId: Long, val userType: WalletOwner,
    val walletType: WalletType, val amount: Long, val transactionType: TransactionType,
    val status: WalletStatus, val referenceTripId: Long?, val withdrawalId: Long?, val balanceAfter: Long?, val createdAt: Instant, val description: String? = null)
fun WalletTransaction.toDto() = WalletTransactionDto(id, driverId ?: customerId!!,
    if (driverId != null) WalletOwner.DRIVER else WalletOwner.CUSTOMER,
    when (walletType) { "INCOME", "CASH" -> WalletType.CASH; "CUSTOMER_WALLET", "CUSTOMER" -> WalletType.CUSTOMER; else -> WalletType.CREDIT },
    amount, transactionType, status, rideId, withdrawalId, balanceAfter, createdAt, description)

data class DriverCreditStatus(val creditBalance: Long, val minimumCredit: Long, val shortfall: Long,
    val canReceive: Boolean, val blocked: Boolean, val mockTopUpEnabled: Boolean = false)

data class WalletOverview(val balances: List<WalletBalanceDto>, val mockTopUpEnabled: Boolean, val maxTopUpAmount: Long = 50_000_000)
data class WalletPage<T>(val content: List<T>, val number: Int, val size: Int, val totalElements: Long, val totalPages: Int, val last: Boolean)
fun <T : Any> org.springframework.data.domain.Page<T>.toWalletPage() = WalletPage(content, number, size, totalElements, totalPages, isLast)
