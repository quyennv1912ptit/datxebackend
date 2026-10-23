package com.nhom15.datxebackend.customer.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.customer.dto.CustomerProfileResponse
import com.nhom15.datxebackend.customer.dto.UpdateCustomerAvatarRequest
import com.nhom15.datxebackend.customer.dto.UpdateCustomerProfileRequest
import com.nhom15.datxebackend.customer.service.CustomerProfileService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/customer/profile")
@PreAuthorize("hasRole('CUSTOMER')")
class CustomerProfileController(private val customerProfileService: CustomerProfileService) {
    @GetMapping
    fun get(@CurrentUser actor: AuthUser): ApiResponse<CustomerProfileResponse> =
        ApiResponse.ok(customerProfileService.get(actor))

    @PutMapping
    fun update(
        @CurrentUser actor: AuthUser,
        @Valid @RequestBody request: UpdateCustomerProfileRequest,
    ): ApiResponse<CustomerProfileResponse> = ApiResponse.ok(customerProfileService.update(actor, request))

    @PostMapping("/avatar")
    fun updateAvatar(
        @CurrentUser actor: AuthUser,
        @Valid @RequestBody request: UpdateCustomerAvatarRequest,
    ): ApiResponse<CustomerProfileResponse> = ApiResponse.ok(customerProfileService.updateAvatar(actor, request))
}
