package com.nhom15.datxebackend.tracking.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.port.NearbyDriver
import com.nhom15.datxebackend.tracking.service.LocationService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/location")
class NearbyDriversController(
    private val locationService: LocationService,
) {
    @GetMapping("/nearby-drivers")
    @PreAuthorize("hasRole('ADMIN')")
    fun findNearbyDrivers(
        @RequestParam longitude: Double,
        @RequestParam latitude: Double,
        @RequestParam radius: Double,
    ): ApiResponse<List<NearbyDriver>> = ApiResponse.ok(
        locationService.findNearbyDrivers(GeoPoint(lat = latitude, lng = longitude), radius),
    )
}
