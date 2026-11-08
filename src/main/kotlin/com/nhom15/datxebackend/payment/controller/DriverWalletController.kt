package com.nhom15.datxebackend.payment.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.payment.dto.DriverDailySummary
import com.nhom15.datxebackend.payment.dto.WalletResponse
import com.nhom15.datxebackend.payment.service.PaymentService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/driver/wallet")
@PreAuthorize("hasRole('DRIVER')")
class DriverWalletController(private val paymentService: PaymentService,
    private val wallets: com.nhom15.datxebackend.payment.service.WalletService,
    private val environment: org.springframework.core.env.Environment) {
    @GetMapping("/credit-status")
    fun creditStatus(@CurrentUser actor: AuthUser): ApiResponse<com.nhom15.datxebackend.payment.dto.DriverCreditStatus> {
        val mock = environment.activeProfiles.any { it == "dev" || it == "test" } &&
            environment.getProperty("wallet.mock-topup-enabled", Boolean::class.java, false)
        return ApiResponse.ok(wallets.creditStatus(actor.id).copy(mockTopUpEnabled = mock))
    }

    @GetMapping("/daily-summary")
    fun dailySummary(@CurrentUser actor: AuthUser): ApiResponse<DriverDailySummary> =
        ApiResponse.ok(paymentService.getDriverDailySummary(actor.id))

    @GetMapping
    fun getWallet(@CurrentUser actor: AuthUser): ApiResponse<WalletResponse> =
        ApiResponse.ok(paymentService.getDriverWallet(actor.id))
}
