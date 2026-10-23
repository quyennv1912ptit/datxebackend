package com.nhom15.datxebackend.admin.controller

import com.nhom15.datxebackend.admin.dto.AdminCustomerDetail
import com.nhom15.datxebackend.admin.dto.AdminCustomerFlagResponse
import com.nhom15.datxebackend.admin.dto.AdminCustomerRidesFilter
import com.nhom15.datxebackend.admin.dto.AdminCustomerStatusResponse
import com.nhom15.datxebackend.admin.dto.AdminCustomerSummary
import com.nhom15.datxebackend.admin.dto.AdminWalletTransaction
import com.nhom15.datxebackend.admin.dto.UpdateAdminCustomerFlagRequest
import com.nhom15.datxebackend.admin.dto.UpdateAdminCustomerStatusRequest
import com.nhom15.datxebackend.admin.service.AdminCustomerService
import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.enums.UserStatus
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.ride.dto.response.RideSummaryResponse
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/admin/customers")
@PreAuthorize("hasRole('ADMIN')")
class AdminCustomerController(private val adminCustomerService: AdminCustomerService) {
    @GetMapping
    fun list(
        @CurrentUser actor: AuthUser,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: UserStatus?,
        @RequestParam(required = false) flagged: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "createdAt") sort: String,
    ): ApiResponse<PageResponse<AdminCustomerSummary>> = ApiResponse.ok(
        adminCustomerService.list(actor, q, status, flagged, page, size, sort),
    )

    @GetMapping("/{id}")
    fun detail(@CurrentUser actor: AuthUser, @PathVariable id: Long): ApiResponse<AdminCustomerDetail> =
        ApiResponse.ok(adminCustomerService.detail(actor, id))

    @GetMapping("/{id}/rides")
    fun rides(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false, name = "from") from: LocalDate?,
        @RequestParam(required = false, name = "to") to: LocalDate?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<RideSummaryResponse>> = ApiResponse.ok(
        adminCustomerService.rides(actor, id, AdminCustomerRidesFilter(status, from, to, page, size)),
    )

    @GetMapping("/{id}/wallet-transactions")
    fun walletTransactions(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<AdminWalletTransaction>> =
        ApiResponse.ok(adminCustomerService.walletTransactions(actor, id, page, size))

    @PutMapping("/{id}/status")
    fun updateStatus(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateAdminCustomerStatusRequest,
    ): ApiResponse<AdminCustomerStatusResponse> = ApiResponse.ok(adminCustomerService.updateStatus(actor, id, request))

    @PutMapping("/{id}/flag")
    fun updateFlag(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateAdminCustomerFlagRequest,
    ): ApiResponse<AdminCustomerFlagResponse> = ApiResponse.ok(adminCustomerService.updateFlag(actor, id, request))
}
