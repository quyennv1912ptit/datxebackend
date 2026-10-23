package com.nhom15.datxebackend.tracking.controller

import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.tracking.dto.DriverAvailabilityRequest
import com.nhom15.datxebackend.tracking.dto.DriverAvailabilityResponse
import com.nhom15.datxebackend.tracking.service.DriverPresenceService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/driver")
@PreAuthorize("hasRole('DRIVER')")
class DriverPresenceController(
    private val driverPresenceService: DriverPresenceService,
) {
    @PutMapping("/availability")
    fun setAvailability(
        @CurrentUser user: AuthUser,
        @RequestBody request: DriverAvailabilityRequest,
    ): ApiResponse<DriverAvailabilityResponse> = ApiResponse.ok(
        DriverAvailabilityResponse(driverPresenceService.setOnline(user.id, request.online, request.vehicleType)),
    )
}
