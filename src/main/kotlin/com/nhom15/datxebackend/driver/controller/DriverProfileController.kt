package com.nhom15.datxebackend.driver.controller

import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.driver.dto.DriverProfileResponse
import com.nhom15.datxebackend.driver.dto.UpdateDriverProfileRequest
import com.nhom15.datxebackend.driver.service.DriverProfileService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/driver")
@PreAuthorize("hasRole('DRIVER')")
class DriverProfileController(
    private val driverProfileService: DriverProfileService,
) {
    @GetMapping("/profile")
    fun getProfile(@CurrentUser user: AuthUser): ApiResponse<DriverProfileResponse> =
        ApiResponse.ok(driverProfileService.get(user.id))

    @PutMapping("/profile")
    fun updateProfile(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: UpdateDriverProfileRequest,
    ): ApiResponse<DriverProfileResponse> =
        ApiResponse.ok(driverProfileService.update(user.id, request))
}
