package com.nhom15.datxebackend.admin.service

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.nhom15.datxebackend.admin.dto.AdminCustomerDetail
import com.nhom15.datxebackend.admin.dto.AdminCustomerFilter
import com.nhom15.datxebackend.admin.dto.AdminCustomerFlagResponse
import com.nhom15.datxebackend.admin.dto.AdminCustomerRidesFilter
import com.nhom15.datxebackend.admin.dto.AdminCustomerStatusResponse
import com.nhom15.datxebackend.admin.dto.AdminCustomerSummary
import com.nhom15.datxebackend.admin.dto.AdminWalletTransaction
import com.nhom15.datxebackend.admin.dto.UpdateAdminCustomerFlagRequest
import com.nhom15.datxebackend.admin.dto.UpdateAdminCustomerStatusRequest
import com.nhom15.datxebackend.admin.repository.AdminCustomerQueryRepository
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.UserStatus
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.ride.dto.response.RideSummaryResponse
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.ride.service.state.RideTransitionCommand
import com.nhom15.datxebackend.ride.service.state.RideTransitionEvent
import com.nhom15.datxebackend.ride.service.state.SYSTEM_RIDE_ACTOR
import com.nhom15.datxebackend.vehicle.repository.JdbcVehicleRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
class AdminCustomerService(
    private val queryRepository: AdminCustomerQueryRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: JdbcVehicleRepository,
    private val rideRepository: RideRepository,
    private val rideStateMachine: RideStateMachine,
    private val jdbcTemplate: JdbcTemplate,
    private val firebaseAuth: FirebaseAuth,
    private val authenticatedUserCache: AuthenticatedUserCache,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun list(
        actor: AuthUser,
        q: String?,
        status: UserStatus?,
        flagged: Boolean?,
        page: Int,
        size: Int,
        rawSort: String,
    ): PageResponse<AdminCustomerSummary> {
        requireAdmin(actor)
        validatePage(page, size)
        val sort = rawSort.trim().substringBefore(',')
        if (sort !in ALLOWED_SORTS) throw BadRequestException(message = "sort chỉ hỗ trợ createdAt, totalTrips hoặc cancelRate30d")
        val filter = AdminCustomerFilter(q, status, flagged, page, size, sort)
        val total = queryRepository.count(filter)
        val totalPages = pageCount(total, size)
        return PageResponse(queryRepository.findPage(filter), page, size, total, totalPages, page + 1 < totalPages)
    }

    @Transactional(readOnly = true)
    fun detail(actor: AuthUser, customerId: Long): AdminCustomerDetail {
        requireAdmin(actor)
        val summary = queryRepository.findById(customerId) ?: throw notFoundCustomer()
        val fields = queryRepository.findDetailFields(customerId) ?: throw notFoundCustomer()
        val recentRides = queryRepository.findRides(customerId, AdminCustomerRidesFilter(page = 0, size = 5), null)
        return AdminCustomerDetail(
            id = summary.id,
            fullName = summary.fullName,
            phoneNumber = summary.phoneNumber,
            email = summary.email,
            status = summary.status,
            isFlagged = summary.isFlagged,
            totalTrips = summary.totalTrips,
            totalCancelled = summary.totalCancelled,
            cancelRate30d = summary.cancelRate30d,
            averageRating = summary.averageRating,
            walletBalance = summary.walletBalance,
            lastLoginAt = summary.lastLoginAt,
            createdAt = summary.createdAt,
            dateOfBirth = fields.dateOfBirth,
            gender = fields.gender,
            defaultPaymentMethod = fields.defaultPaymentMethod,
            vehicles = vehicleRepository.findAllByOwnerId(customerId),
            recentRides = recentRides,
        )
    }

    @Transactional(readOnly = true)
    fun rides(actor: AuthUser, customerId: Long, filter: AdminCustomerRidesFilter): PageResponse<RideSummaryResponse> {
        requireAdmin(actor)
        ensureCustomerExists(customerId)
        validatePage(filter.page, filter.size)
        val status = filter.status?.trim()?.takeIf(String::isNotEmpty)?.let { raw ->
            runCatching { RideStatus.valueOf(raw.uppercase()) }
                .getOrElse { throw BadRequestException(message = "status chuyến không hợp lệ: $raw") }
        }
        if (filter.from != null && filter.to != null && filter.to.isBefore(filter.from)) {
            throw BadRequestException(message = "to phải cùng ngày hoặc sau from")
        }
        val total = queryRepository.countRides(customerId, filter, status)
        val totalPages = pageCount(total, filter.size)
        return PageResponse(
            queryRepository.findRides(customerId, filter, status),
            filter.page,
            filter.size,
            total,
            totalPages,
            filter.page + 1 < totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun walletTransactions(actor: AuthUser, customerId: Long, page: Int, size: Int): PageResponse<AdminWalletTransaction> {
        requireAdmin(actor)
        ensureCustomerExists(customerId)
        validatePage(page, size)
        val total = queryRepository.countWalletTransactions(customerId)
        val totalPages = pageCount(total, size)
        return PageResponse(
            queryRepository.findWalletTransactions(customerId, page, size),
            page,
            size,
            total,
            totalPages,
            page + 1 < totalPages,
        )
    }

    @Transactional
    fun updateStatus(actor: AuthUser, customerId: Long, request: UpdateAdminCustomerStatusRequest): AdminCustomerStatusResponse {
        requireAdmin(actor)
        val customer = lockCustomer(customerId)
        val reason = request.reason?.trim()?.takeIf(String::isNotEmpty)
        if (request.status != UserStatus.ACTIVE && reason == null) {
            throw BadRequestException(message = "reason bắt buộc khi khóa tài khoản")
        }
        val previousStatus = customer.status
        if (previousStatus == request.status) return AdminCustomerStatusResponse(customerId, customer.status)

        customer.status = request.status
        customerRepository.save(customer)
        auditStatus(customerId, actor.id, previousStatus, request.status, reason)

        if (request.status == UserStatus.BANNED) {
            val cancellableRides = rideRepository.findByCustomerIdAndStatusIn(
                customerId,
                listOf(RideStatus.PENDING, RideStatus.SEARCHING),
            )
            cancellableRides.forEach { ride ->
                rideStateMachine.transition(
                    RideTransitionCommand(
                        rideId = ride.id,
                        event = RideTransitionEvent.CANCEL,
                        actor = SYSTEM_RIDE_ACTOR,
                        note = "Tài khoản khách bị khóa: $reason",
                        cancelReason = reason?.take(255),
                    ),
                )
            }
        }

        afterCommit {
            authenticatedUserCache.evict(customer.firebaseUid)
            if (request.status == UserStatus.BANNED) revokeRefreshTokens(customer.id, customer.firebaseUid)
        }
        return AdminCustomerStatusResponse(customerId, request.status)
    }

    @Transactional
    fun updateFlag(actor: AuthUser, customerId: Long, request: UpdateAdminCustomerFlagRequest): AdminCustomerFlagResponse {
        requireAdmin(actor)
        val customer = lockCustomer(customerId)
        val previousFlagged = customer.isFlagged == true
        if (previousFlagged != request.flagged) {
            customer.isFlagged = request.flagged
            customerRepository.save(customer)
        }
        jdbcTemplate.update(
            """INSERT INTO admin_customer_actions
               (customer_id, admin_id, action, previous_flagged, new_flagged, note)
               VALUES (?, ?, 'FLAG', ?, ?, ?)""".trimIndent(),
            customerId,
            actor.id,
            previousFlagged,
            request.flagged,
            request.note?.trim()?.takeIf(String::isNotEmpty),
        )
        return AdminCustomerFlagResponse(customerId, request.flagged)
    }

    private fun auditStatus(
        customerId: Long,
        adminId: Long,
        previous: UserStatus,
        next: UserStatus,
        reason: String?,
    ) {
        jdbcTemplate.update(
            """INSERT INTO admin_customer_actions
               (customer_id, admin_id, action, previous_status, new_status, note)
               VALUES (?, ?, 'STATUS', ?, ?, ?)""".trimIndent(),
            customerId,
            adminId,
            previous.name,
            next.name,
            reason,
        )
    }

    private fun revokeRefreshTokens(customerId: Long, firebaseUid: String) {
        try {
            firebaseAuth.revokeRefreshTokens(firebaseUid)
        } catch (exception: FirebaseAuthException) {
            log.error("Không thể revoke Firebase refresh tokens cho customer {} sau khi khóa", customerId, exception)
        } catch (exception: RuntimeException) {
            log.error("Lỗi khi revoke Firebase refresh tokens cho customer {} sau khi khóa", customerId, exception)
        }
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }

    private fun lockCustomer(customerId: Long): Customer = customerRepository.findByIdForUpdate(customerId)
        .filter { it.deletedAt == null }
        .orElseThrow { notFoundCustomer() }

    private fun ensureCustomerExists(customerId: Long) {
        if (queryRepository.findById(customerId) == null) throw notFoundCustomer()
    }

    private fun notFoundCustomer() = NotFoundException(ErrorCode.USER_NOT_FOUND, "Không tìm thấy khách hàng")

    private fun requireAdmin(actor: AuthUser) {
        if (actor.role != Role.ADMIN) throw ForbiddenException(message = "Chỉ admin được quản lý khách hàng")
    }

    private fun validatePage(page: Int, size: Int) {
        if (page < 0) throw BadRequestException(message = "page phải lớn hơn hoặc bằng 0")
        if (size !in 1..100) throw BadRequestException(message = "size phải nằm trong khoảng 1 đến 100")
    }

    private fun pageCount(total: Long, size: Int): Int = if (total == 0L) 0 else ((total + size - 1) / size).toInt()

    private companion object {
        val ALLOWED_SORTS = setOf("createdAt", "totalTrips", "cancelRate30d")
    }
}
