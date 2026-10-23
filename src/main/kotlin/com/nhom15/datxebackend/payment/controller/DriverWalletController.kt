package com.nhom15.datxebackend.payment.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.payment.dto.WalletResponse
import com.nhom15.datxebackend.payment.service.PaymentService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/driver/wallet")
@PreAuthorize("hasRole('DRIVER')")
class DriverWalletController(private val paymentService: PaymentService) {
    @GetMapping
    fun getWallet(@CurrentUser actor: AuthUser): ApiResponse<WalletResponse> =
        ApiResponse.ok(paymentService.getDriverWallet(actor.id))
}
