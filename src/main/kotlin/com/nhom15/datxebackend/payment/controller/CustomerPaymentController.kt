package com.nhom15.datxebackend.payment.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.security.*
import com.nhom15.datxebackend.payment.service.PaymentMethodService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

data class ChangePaymentMethodBody(val method: PaymentMethod, val expectedMethod: PaymentMethod)
@RestController
@RequestMapping("/api/customer/rides/{rideId}/payment")
@PreAuthorize("hasRole('CUSTOMER')")
class CustomerPaymentController(private val service: PaymentMethodService) {
    @PutMapping("/method")
    fun change(@CurrentUser actor: AuthUser, @PathVariable rideId: Long,
        @RequestHeader("Idempotency-Key") key: String, @RequestBody body: ChangePaymentMethodBody) =
        ApiResponse.ok(service.change(actor, rideId, body.method, body.expectedMethod, key))
}
