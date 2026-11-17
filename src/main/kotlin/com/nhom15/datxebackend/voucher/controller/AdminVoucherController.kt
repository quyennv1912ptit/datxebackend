package com.nhom15.datxebackend.voucher.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.voucher.dto.AdminVoucherCreatedResponse
import com.nhom15.datxebackend.voucher.dto.BudgetLedgerItemResponse
import com.nhom15.datxebackend.voucher.dto.CampaignClaimantResponse
import com.nhom15.datxebackend.voucher.dto.CreateAdminVoucherRequest
import com.nhom15.datxebackend.voucher.service.AdminVoucherService
import com.nhom15.datxebackend.voucher.service.PromoBudgetService
import com.nhom15.datxebackend.voucher.service.PromoBudgetSummary
import jakarta.validation.Valid
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/vouchers")
@PreAuthorize("hasRole('ADMIN')")
class AdminVoucherController(
    private val adminVoucherService: AdminVoucherService,
    private val promoBudget: PromoBudgetService,
) {
    data class BudgetRequest(@field:PositiveOrZero val budgetLimit: Long)
    data class CampaignStatusRequest(val active: Boolean? = null)

    @GetMapping
    fun list(@CurrentUser actor: AuthUser): ApiResponse<List<AdminVoucherCreatedResponse>> =
        ApiResponse.ok(adminVoucherService.list(actor))

    @GetMapping("/{id}/claims")
    fun listClaimants(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
    ): ApiResponse<List<CampaignClaimantResponse>> =
        ApiResponse.ok(adminVoucherService.listClaimants(actor, id))

    @PatchMapping("/{id}/status")
    fun updateCampaignStatus(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @RequestBody request: CampaignStatusRequest,
    ): ApiResponse<Unit> {
        val nextActive = request.active ?: throw BadRequestException(message = "Thiếu trạng thái active")
        adminVoucherService.setCampaignActive(actor, id, nextActive)
        return ApiResponse.ok(Unit)
    }

    @GetMapping("/budget")
    fun budget(@CurrentUser actor: AuthUser): ApiResponse<PromoBudgetSummary> =
        ApiResponse.ok(promoBudget.summary())

    @GetMapping("/budget/ledger")
    fun budgetLedger(@CurrentUser actor: AuthUser): ApiResponse<List<BudgetLedgerItemResponse>> =
        ApiResponse.ok(adminVoucherService.listBudgetLedger(actor))

    @PutMapping("/budget")
    fun setBudget(
        @CurrentUser actor: AuthUser,
        @Valid @RequestBody request: BudgetRequest,
    ): ApiResponse<PromoBudgetSummary> {
        promoBudget.setLimit(request.budgetLimit)
        return ApiResponse.ok(promoBudget.summary())
    }

    @PostMapping
    fun create(
        @CurrentUser actor: AuthUser,
        @Valid @RequestBody request: CreateAdminVoucherRequest,
    ): ApiResponse<AdminVoucherCreatedResponse> =
        ApiResponse.ok(adminVoucherService.create(actor, request))
}