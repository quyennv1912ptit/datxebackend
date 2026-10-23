package com.nhom15.datxebackend.admin.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.driver.dto.AdminDriverDetail
import com.nhom15.datxebackend.driver.dto.AdminDriverDocumentQueueItem
import com.nhom15.datxebackend.driver.dto.AdminDriverFilter
import com.nhom15.datxebackend.driver.dto.AdminDriverSummary
import com.nhom15.datxebackend.driver.dto.DocumentVerificationStatus
import com.nhom15.datxebackend.driver.dto.DriverDocumentResponse
import com.nhom15.datxebackend.driver.dto.ReviewDriverDocumentRequest
import com.nhom15.datxebackend.driver.dto.SetDriverVerificationStatusRequest
import com.nhom15.datxebackend.driver.dto.VerifyDriverSkillRequest
import com.nhom15.datxebackend.admin.service.AdminDriverService
import jakarta.validation.Valid
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

    @GetMapping("/driver-documents")
    fun documentQueue(
        @CurrentUser actor: AuthUser,
        @RequestParam(defaultValue = "PENDING") status: DocumentVerificationStatus,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<AdminDriverDocumentQueueItem>> =
        ApiResponse.ok(adminDriverService.documentQueue(actor, status, page, size))

    @PutMapping("/driver-documents/{id}/review")
    fun reviewDocument(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: ReviewDriverDocumentRequest,
    ): ApiResponse<DriverDocumentResponse> = ApiResponse.ok(adminDriverService.reviewDocument(actor, id, request))

    @PutMapping("/driver-skills/{id}/verify")
    fun verifySkill(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: VerifyDriverSkillRequest,
    ): ApiResponse<Unit> {
        adminDriverService.verifySkill(actor, id, request)
        return ApiResponse.ok()
    }

    @PutMapping("/drivers/{id}/verification-status")
    fun setVerificationStatus(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: SetDriverVerificationStatusRequest,
    ): ApiResponse<Unit> {
        adminDriverService.setVerificationStatus(actor, id, request)
        return ApiResponse.ok()
    }

    @PostMapping("/drivers/{id}/force-offline")
    fun forceOffline(@CurrentUser actor: AuthUser, @PathVariable id: Long): ApiResponse<Unit> {
        adminDriverService.forceOffline(actor, id)
        return ApiResponse.ok()
    }
}
