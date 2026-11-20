package com.nhom15.datxebackend.driver.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.driver.dto.DriverVerificationSummary
import com.nhom15.datxebackend.driver.service.DriverOnboardingService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/driver")
@PreAuthorize("hasRole('DRIVER')")
class DriverOnboardingController(private val onboardingService: DriverOnboardingService) {
    @GetMapping("/verification")
    fun verification(@CurrentUser actor: AuthUser): ApiResponse<DriverVerificationSummary> =
        ApiResponse.ok(onboardingService.verification(actor))
}
