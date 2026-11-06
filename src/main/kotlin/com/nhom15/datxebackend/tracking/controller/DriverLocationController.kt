package com.nhom15.datxebackend.tracking.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.tracking.dto.DriverLocation
import com.nhom15.datxebackend.tracking.service.DriverLocationIngestService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST fallback for driver GPS. A foreground service keeps sampling even when the
 * long-lived STOMP socket is gone, so dispatch must not depend on the socket alone.
 */
@RestController
@RequestMapping("/api/driver")
@PreAuthorize("hasRole('DRIVER')")
class DriverLocationController(
    private val ingestService: DriverLocationIngestService,
) {
    @PostMapping("/location")
    fun updateLocation(
        @CurrentUser user: AuthUser,
        @RequestBody update: DriverLocation,
    ): ApiResponse<Boolean> = ApiResponse.ok(ingestService.ingest(user.id, update))
}
