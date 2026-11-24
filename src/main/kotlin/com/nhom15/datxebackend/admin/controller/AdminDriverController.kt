package com.nhom15.datxebackend.admin.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.driver.dto.AdminDriverDetail
import com.nhom15.datxebackend.driver.dto.AdminDriverFilter
import com.nhom15.datxebackend.driver.dto.AdminDriverSummary
import com.nhom15.datxebackend.driver.dto.UpdateDriverAccountStatusRequest
import com.nhom15.datxebackend.driver.dto.UpdateDriverWithdrawalFreezeRequest
import com.nhom15.datxebackend.admin.service.AdminDriverService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
class AdminDriverController(private val adminDriverService: AdminDriverService) {
    @GetMapping("/drivers")
    fun listDrivers(
        @CurrentUser actor: AuthUser,
        @RequestParam(required = false) verificationStatus: String?,
        @RequestParam(name = "verification_status", required = false) verificationStatusSnake: String?,
        @RequestParam(required = false) accountStatus: String?,
        @RequestParam(name = "account_status", required = false) accountStatusSnake: String?,
        @RequestParam(required = false) withdrawalFrozen: Boolean?,
        @RequestParam(name = "withdrawal_frozen", required = false) withdrawalFrozenSnake: Boolean?,
        @RequestParam(required = false) online: Boolean?,
        @RequestParam(required = false) flagged: Boolean?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<AdminDriverSummary>> = ApiResponse.ok(
        adminDriverService.list(
            actor,
            AdminDriverFilter(
                verificationStatus = verificationStatusSnake ?: verificationStatus,
                accountStatus = accountStatusSnake ?: accountStatus,
                withdrawalFrozen = withdrawalFrozenSnake ?: withdrawalFrozen,
                online = online,
                flagged = flagged,
                search = search,
                page = page,
                size = size,
            ),
        ),
    )

    @GetMapping("/drivers/{id}")
    fun driverDetail(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
    ): ApiResponse<AdminDriverDetail> = ApiResponse.ok(adminDriverService.detail(actor, id))

    @PostMapping("/drivers/{id}/force-offline")
    fun forceOffline(@CurrentUser actor: AuthUser, @PathVariable id: Long): ApiResponse<Unit> {
        adminDriverService.forceOffline(actor, id)
        return ApiResponse.ok()
    }

    @PutMapping("/drivers/{id}/status")
    fun updateStatus(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @RequestBody request: UpdateDriverAccountStatusRequest,
    ): ApiResponse<AdminDriverDetail> = ApiResponse.ok(adminDriverService.updateAccountStatus(actor, id, request))

    @PutMapping("/drivers/{id}/withdrawal-freeze")
    fun updateWithdrawalFreeze(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @RequestBody request: UpdateDriverWithdrawalFreezeRequest,
    ): ApiResponse<AdminDriverDetail> = ApiResponse.ok(adminDriverService.updateWithdrawalFreeze(actor, id, request))
}
