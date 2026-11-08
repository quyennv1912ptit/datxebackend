package com.nhom15.datxebackend.payment.controller

import com.nhom15.datxebackend.admin.dto.AdminSearchFilter
import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.security.*
import com.nhom15.datxebackend.payment.entity.*
import com.nhom15.datxebackend.payment.dto.*
import com.nhom15.datxebackend.payment.service.*
import com.fasterxml.jackson.annotation.JsonAlias
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

data class WithdrawalBody(val amount: Long,
    @param:JsonAlias("wallet_type") val walletType: WalletType = WalletType.CASH)
data class ReviewBody(val action: String, @param:JsonAlias("reject_reason") val rejectReason: String? = null)
data class DriverTopUpBody(val amount: Long)
data class TopUpBody(
    @param:JsonAlias("user_id") @field:Positive val userId: Long,
    @param:JsonAlias("wallet_type") val walletType: WalletType,
    val amount: Long,
    @param:JsonAlias("user_type") val userType: WalletOwner =
        if (walletType == WalletType.CUSTOMER) WalletOwner.CUSTOMER else WalletOwner.DRIVER,
)

@RestController
@RequestMapping("/api/driver/withdrawals")
@PreAuthorize("hasRole('DRIVER')")
class DriverWithdrawalController(private val service: WithdrawalService) {
    @PostMapping
    fun create(@CurrentUser actor: AuthUser, @RequestHeader("Idempotency-Key") key: String,
               @Valid @RequestBody body: WithdrawalBody) = ApiResponse.ok(service.create(actor.id, body.amount, key, body.walletType).toDto())
    @GetMapping("/{id}")
    fun get(@CurrentUser actor: AuthUser, @PathVariable id: Long) = ApiResponse.ok(service.get(actor.id, id).toDto())
    @GetMapping
    fun list(@CurrentUser actor: AuthUser, @RequestParam(defaultValue = "0") page: Int) = ApiResponse.ok(service.list(actor.id, page).map { it.toDto() }.toWalletPage())
}

@RestController
@RequestMapping("/api/admin/withdrawals")
@PreAuthorize("hasRole('ADMIN')")
class AdminWithdrawalController(private val service: WithdrawalService) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: WithdrawalStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @ModelAttribute filter: AdminSearchFilter,
    ): ApiResponse<PageResponse<AdminWithdrawalDto>> = ApiResponse.ok(service.adminList(status, page, size, filter))

    @PostMapping("/{id}/review")
    fun review(@CurrentUser actor: AuthUser, @PathVariable id: Long, @RequestBody body: ReviewBody) =
        ApiResponse.ok(service.review(id, actor.id, try { WithdrawalAction.valueOf(body.action) }
            catch (_: IllegalArgumentException) { throw com.nhom15.datxebackend.payment.exception.InvalidWithdrawalActionException("Action phải là APPROVE hoặc REJECT") }, body.rejectReason).toDto())
}

@RestController
@Profile("dev", "test", "demo")
@ConditionalOnProperty(name = ["wallet.mock-topup-enabled"], havingValue = "true")
@RequestMapping("/api/mock/wallet")
@PreAuthorize("hasRole('ADMIN')")
class MockWalletController(private val service: WalletService) {
    @PostMapping("/top-up")
    fun topUp(@Valid @RequestBody body: TopUpBody) =
        ApiResponse.ok(service.topUp(body.userId, body.userType, body.walletType, body.amount).toDto())
}

@RestController
@RequestMapping("/api/wallet")
@PreAuthorize("hasAnyRole('DRIVER','CUSTOMER')")
class WalletQueryController(private val service: WalletService, private val environment: org.springframework.core.env.Environment) {
    private fun owner(actor: AuthUser) = if (actor.role == com.nhom15.datxebackend.common.enums.Role.DRIVER) WalletOwner.DRIVER else WalletOwner.CUSTOMER
    @GetMapping("/overview")
    fun overview(@CurrentUser actor: AuthUser) = ApiResponse.ok(WalletOverview(service.balances(actor.id, owner(actor)),
        environment.activeProfiles.any { it == "dev" || it == "test" || it == "demo" } && environment.getProperty("wallet.mock-topup-enabled", Boolean::class.java, false)))
    @GetMapping
    fun balances(@CurrentUser actor: AuthUser) = ApiResponse.ok(service.balances(actor.id, owner(actor)))
    @GetMapping("/transactions")
    fun history(@CurrentUser actor: AuthUser, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(required = false) walletType: WalletType?) =
        ApiResponse.ok(service.history(actor.id, owner(actor), page, walletType).toWalletPage())
}

@RestController
@Profile("dev", "test", "demo")
@ConditionalOnProperty(name = ["wallet.mock-topup-enabled"], havingValue = "true")
@RequestMapping("/api/driver/wallet")
@PreAuthorize("hasRole('DRIVER')")
class DriverMockTopUpController(private val service: DriverMockTopUpService) {
    @PostMapping("/top-up")
    fun topUp(@CurrentUser actor: AuthUser, @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: DriverTopUpBody): ApiResponse<Unit> {
        service.topUp(actor.id, body.amount, key)
        return ApiResponse.ok()
    }
}

@RestController
@Profile("dev", "test", "demo")
@ConditionalOnProperty(name = ["wallet.mock-topup-enabled"], havingValue = "true")
@RequestMapping("/api/customer/wallet")
@PreAuthorize("hasRole('CUSTOMER')")
class CustomerMockTopUpController(private val service: CustomerMockTopUpService) {
    @PostMapping("/top-up")
    fun topUp(@CurrentUser actor: AuthUser, @RequestHeader("Idempotency-Key") key: String,
        @RequestBody body: DriverTopUpBody): ApiResponse<Unit> {
        service.topUp(actor.id, body.amount, key)
        return ApiResponse.ok()
    }
}
