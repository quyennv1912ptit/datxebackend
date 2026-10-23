package com.nhom15.datxebackend.driver.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.driver.dto.DriverDocumentResponse
import com.nhom15.datxebackend.driver.dto.DriverSkillResponse
import com.nhom15.datxebackend.driver.dto.DriverVerificationSummary
import com.nhom15.datxebackend.driver.dto.SubmitDriverDocumentRequest
import com.nhom15.datxebackend.driver.dto.UpdateDriverSkillsRequest
import com.nhom15.datxebackend.driver.service.DriverOnboardingService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/driver")
@PreAuthorize("hasRole('DRIVER')")
class DriverOnboardingController(private val onboardingService: DriverOnboardingService) {
    @PostMapping("/documents")
    fun submitDocument(
        @CurrentUser actor: AuthUser,
        @Valid @RequestBody request: SubmitDriverDocumentRequest,
    ): ResponseEntity<ApiResponse<DriverDocumentResponse>> = ResponseEntity
        .status(HttpStatus.CREATED)
        .body(ApiResponse.ok(onboardingService.submitDocument(actor, request)))

    @GetMapping("/documents")
    fun documents(@CurrentUser actor: AuthUser): ApiResponse<List<DriverDocumentResponse>> =
        ApiResponse.ok(onboardingService.documents(actor))

    @GetMapping("/verification")
    fun verification(@CurrentUser actor: AuthUser): ApiResponse<DriverVerificationSummary> =
        ApiResponse.ok(onboardingService.verification(actor))

    @GetMapping("/skills")
    fun skills(@CurrentUser actor: AuthUser): ApiResponse<List<DriverSkillResponse>> =
        ApiResponse.ok(onboardingService.skills(actor))

    @PutMapping("/skills")
    fun updateSkills(
        @CurrentUser actor: AuthUser,
        @Valid @RequestBody request: UpdateDriverSkillsRequest,
    ): ApiResponse<List<DriverSkillResponse>> = ApiResponse.ok(onboardingService.updateSkills(actor, request))
}
