package com.nhom15.datxebackend.voucher.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.voucher.dto.AdminVoucherCreatedResponse
import com.nhom15.datxebackend.voucher.dto.CreateAdminVoucherRequest
import com.nhom15.datxebackend.voucher.service.AdminVoucherService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/vouchers")
@PreAuthorize("hasRole('ADMIN')")
class AdminVoucherController(
    private val adminVoucherService: AdminVoucherService,
) {
    @PostMapping
    fun create(
        @CurrentUser actor: AuthUser,
        @Valid @RequestBody request: CreateAdminVoucherRequest,
    ): ApiResponse<AdminVoucherCreatedResponse> =
        ApiResponse.ok(adminVoucherService.create(actor, request))
}
