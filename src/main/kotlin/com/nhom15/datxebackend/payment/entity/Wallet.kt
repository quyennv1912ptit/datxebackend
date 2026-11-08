package com.nhom15.datxebackend.payment.entity

import jakarta.persistence.*
import java.time.Instant

enum class WalletOwner { DRIVER, CUSTOMER }
enum class WalletType { CREDIT, CASH, CUSTOMER }
enum class TransactionType { TOP_UP, WITHDRAW, RIDE_PAYMENT, COMMISSION_FEE, REFUND }
enum class WalletStatus { PENDING, SUCCESS, FAILED }
enum class WithdrawalStatus { PENDING, SUCCESS, REJECTED }
enum class WithdrawalAction { APPROVE, REJECT }

@Entity
@Table(name = "wallets", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "user_type", "wallet_type"])])
class Wallet(
    @Column(name = "user_id", nullable = false) val userId: Long,
    @Enumerated(EnumType.STRING) @Column(name = "user_type", nullable = false) val userType: WalletOwner,
    @Enumerated(EnumType.STRING) @Column(name = "wallet_type", nullable = false) val walletType: WalletType,
    @Column(nullable = false) var balance: Long = 0,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
)

@Entity
@Table(name = "wallet_transactions")
class WalletTransaction(
    @Column(name = "driver_id") val driverId: Long? = null,
    @Column(name = "customer_id") val customerId: Long? = null,
    @Column(name = "wallet_type", nullable = false) val walletType: String,
    @Column(nullable = false) val amount: Long,
    @Column(name = "type", nullable = false) val legacyType: String,
    @Enumerated(EnumType.STRING) @Column(name = "transaction_type", nullable = false) val transactionType: TransactionType,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: WalletStatus = WalletStatus.SUCCESS,
    @Column(name = "ride_id") val rideId: Long? = null,
    @Column(name = "balance_after") val balanceAfter: Long?,
    @Column(name = "withdrawal_id") val withdrawalId: Long? = null,
    @Column val description: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
)

@Entity
@Table(name = "withdrawal_requests")
class WithdrawalRequest(
    @Column(name = "driver_id", nullable = false) val driverId: Long,
    @Column(nullable = false) val amount: Long,
    @Column(name = "idempotency_key", nullable = false) val idempotencyKey: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: WithdrawalStatus = WithdrawalStatus.PENDING,
    @Column(name = "reject_reason", length = 500) var rejectReason: String? = null,
    @Column(name = "reviewed_by") var reviewedBy: Long? = null,
    @Column(name = "reviewed_at") var reviewedAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long = 0,
)
