package com.nhom15.datxebackend.payment.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.payment.dto.CashPaymentConfirmationResponse
import com.nhom15.datxebackend.payment.service.PaymentService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/driver/rides/{rideId}/payment")
@PreAuthorize("hasRole('DRIVER')")
class CashPaymentController(private val paymentService: PaymentService) {
    @PostMapping("/cash/confirm")
    fun confirmCashReceived(
        @CurrentUser actor: AuthUser,
        @PathVariable rideId: Long,
    ): ApiResponse<CashPaymentConfirmationResponse> =
        ApiResponse.ok(paymentService.confirmCashReceived(actor, rideId))
}
