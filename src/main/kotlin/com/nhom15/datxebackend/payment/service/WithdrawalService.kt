package com.nhom15.datxebackend.payment.service

import com.nhom15.datxebackend.admin.dto.AdminSearchFilter
import com.nhom15.datxebackend.admin.repository.AdminWithdrawalSpecifications
import com.nhom15.datxebackend.common.exception.*
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.entity.*
import com.nhom15.datxebackend.payment.dto.AdminWithdrawalDto
import com.nhom15.datxebackend.payment.exception.*
import com.nhom15.datxebackend.payment.repository.*
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class WithdrawalService(
    private val requests: WithdrawalRepository,
    private val transactions: WalletTransactionRepository,
    private val drivers: DriverRepository,
    private val wallets: WalletService,
    private val events: org.springframework.context.ApplicationEventPublisher,
) {
    @Transactional
    fun create(driverId: Long, amount: Long, key: String, walletType: WalletType = WalletType.CASH): WithdrawalRequest {
        if (walletType != WalletType.CASH) throw InvalidWalletTypeException()
        wallets.requireAmount(amount)
        if (key.isBlank() || key.length > 100) throw BadRequestException(message = "Idempotency-Key cần từ 1 đến 100 ký tự")
        drivers.findByIdForUpdate(driverId).orElseThrow { NotFoundException() }
        requests.findByDriverIdAndIdempotencyKey(driverId, key)?.let {
            if (it.amount != amount) throw ConflictException(message = "Idempotency-Key đã dùng với số tiền khác")
            return it
        }
        val request = requests.saveAndFlush(WithdrawalRequest(driverId, amount, key))
        wallets.hold(driverId, amount, request.id)
        return request
    }

    @Transactional
    fun review(id: Long, adminId: Long, action: WithdrawalAction, rejectReason: String? = null): WithdrawalRequest {
        val request = requests.lockRequest(id) ?: throw WithdrawalRequestNotFoundException()
        if (request.status != WithdrawalStatus.PENDING) throw InvalidWithdrawalActionException()
        val reason = if (action == WithdrawalAction.REJECT) rejectReason?.trim() else null
        if (action == WithdrawalAction.REJECT && (reason.isNullOrBlank() || reason.length > 500))
            throw BadRequestException(message = "Lý do từ chối cần từ 1 đến 500 ký tự")
        val debit = transactions.findByWithdrawalIdAndTransactionType(id, TransactionType.WITHDRAW)
            ?: throw ConflictException(message = "Thiếu giao dịch giữ tiền")
        if (debit.status != WalletStatus.PENDING || debit.driverId != request.driverId || debit.amount != -request.amount ||
            debit.walletType !in setOf("CASH", "INCOME")) throw ConflictException(message = "Giao dịch giữ tiền không khớp yêu cầu rút")
        if (action == WithdrawalAction.REJECT) wallets.release(request.driverId, request.amount, request.id)
        debit.status = if (action == WithdrawalAction.APPROVE) WalletStatus.SUCCESS else WalletStatus.FAILED
        request.status = if (action == WithdrawalAction.APPROVE) WithdrawalStatus.SUCCESS else WithdrawalStatus.REJECTED
        request.rejectReason = reason
        request.reviewedBy = adminId
        request.reviewedAt = Instant.now()
        events.publishEvent(com.nhom15.datxebackend.payment.event.WithdrawalReviewed(
            request.id, request.driverId, request.amount, request.status, request.rejectReason))
        return request
    }

    @Transactional(readOnly = true)
    fun get(driverId: Long, id: Long): WithdrawalRequest = requests.findById(id).orElseThrow { WithdrawalRequestNotFoundException() }
        .takeIf { it.driverId == driverId } ?: throw WithdrawalRequestNotFoundException()

    @Transactional(readOnly = true)
    fun list(driverId: Long, page: Int) = requests.findByDriverIdOrderByCreatedAtDesc(driverId, PageRequest.of(page.coerceAtLeast(0), 20))

    /** Admin xem hàng đợi rút tiền, lọc theo trạng thái; trả kèm tài khoản ngân hàng hiện tại của tài xế. */
    @Transactional(readOnly = true)
    fun adminList(status: WithdrawalStatus?, page: Int, size: Int, filter: AdminSearchFilter = AdminSearchFilter()): PageResponse<AdminWithdrawalDto> {
        if (page < 0) throw BadRequestException(message = "page phải lớn hơn hoặc bằng 0")
        if (size !in 1..100) throw BadRequestException(message = "size phải nằm trong khoảng 1 đến 100")
        filter.validate()
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"))
        val result = if (filter.q.isNullOrBlank() && filter.createdFrom == null && filter.createdTo == null)
            requests.findByStatus(status, pageable)
        else requests.findAll(AdminWithdrawalSpecifications.matching(status, filter), pageable)
        val driversById = drivers.findAllById(result.content.map { it.driverId }.toSet())
            .associateBy { it.id }
        return PageResponse.from(result) { request ->
            val driver = driversById[request.driverId]
            AdminWithdrawalDto(
                id = request.id,
                driverId = request.driverId,
                driverName = driver?.fullName,
                driverPhoneNumber = driver?.phoneNumber,
                bankName = driver?.bankName,
                bankAccountNumber = driver?.bankAccountNumber,
                amount = request.amount,
                status = request.status,
                createdAt = request.createdAt,
                reviewedAt = request.reviewedAt,
                reviewedBy = request.reviewedBy,
                rejectReason = request.rejectReason,
            )
        }
    }
}
