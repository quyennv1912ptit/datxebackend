package com.nhom15.datxebackend.voucher.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.voucher.dto.VoucherResponse
import com.nhom15.datxebackend.voucher.service.VoucherService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/customer/vouchers")
class VoucherController(private val voucherService: VoucherService) {
    @GetMapping
    fun list(@CurrentUser actor: AuthUser): ApiResponse<List<VoucherResponse>> =
        ApiResponse.ok(voucherService.listAvailable(actor))
}
