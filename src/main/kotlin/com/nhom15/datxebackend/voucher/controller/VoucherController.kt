package com.nhom15.datxebackend.voucher.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.voucher.dto.VoucherResponse
import com.nhom15.datxebackend.voucher.service.VoucherService
import com.nhom15.datxebackend.voucher.dto.VoucherCampaignResponse
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/customer/vouchers")
class VoucherController(private val voucherService: VoucherService) {
    @GetMapping
    fun list(@CurrentUser actor: AuthUser): ApiResponse<List<VoucherResponse>> =
        ApiResponse.ok(voucherService.listAvailable(actor))

    @GetMapping("/campaigns")
    fun campaigns(@CurrentUser actor: AuthUser): ApiResponse<List<VoucherCampaignResponse>> =
        ApiResponse.ok(voucherService.listClaimableCampaigns(actor))

    @PostMapping("/campaigns/{id}/claim")
    fun claim(@CurrentUser actor: AuthUser, @PathVariable id: Long): ApiResponse<VoucherResponse> =
        ApiResponse.ok(voucherService.claimCampaign(actor, id))
}
