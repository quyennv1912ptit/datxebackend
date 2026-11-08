package com.nhom15.datxebackend.payment.repository

import com.nhom15.datxebackend.payment.entity.*
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.*

interface WalletRepository : JpaRepository<Wallet, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId and w.userType = :owner and w.walletType = :type")
    fun lockWallet(userId: Long, owner: WalletOwner, type: WalletType): Wallet?
}

interface WalletTransactionRepository : JpaRepository<WalletTransaction, Long> {
    @Query("select t from WalletTransaction t where ((:owner = 'DRIVER' and t.driverId = :userId) or (:owner = 'CUSTOMER' and t.customerId = :userId)) and (:walletType is null or t.walletType = :walletType) order by t.createdAt desc, t.id desc")
    fun history(userId: Long, owner: String, walletType: String?, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<WalletTransaction>
    fun findByDriverIdAndRideIdAndLegacyTypeAndStatus(driverId: Long, rideId: Long, legacyType: String, status: WalletStatus): WalletTransaction?
    fun existsByRideIdAndTransactionType(rideId: Long, transactionType: TransactionType): Boolean
    fun findByWithdrawalIdAndTransactionType(withdrawalId: Long, transactionType: TransactionType): WalletTransaction?
}

interface WithdrawalRepository : JpaRepository<WithdrawalRequest, Long>, JpaSpecificationExecutor<WithdrawalRequest> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WithdrawalRequest r where r.id = :id")
    fun lockRequest(id: Long): WithdrawalRequest?
    fun findByDriverIdAndIdempotencyKey(driverId: Long, idempotencyKey: String): WithdrawalRequest?
    fun findByDriverIdOrderByCreatedAtDesc(driverId: Long, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<WithdrawalRequest>

    /** Hàng đợi admin; status null nghĩa là lấy tất cả trạng thái. */
    @Query("select r from WithdrawalRequest r where (:status is null or r.status = :status)")
    fun findByStatus(status: WithdrawalStatus?, pageable: org.springframework.data.domain.Pageable): org.springframework.data.domain.Page<WithdrawalRequest>
}
