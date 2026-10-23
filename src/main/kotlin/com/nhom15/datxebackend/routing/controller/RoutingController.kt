package com.nhom15.datxebackend.routing.controller

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.routing.model.Route
import com.nhom15.datxebackend.routing.service.MapboxRateLimiter
import com.nhom15.datxebackend.routing.service.MapService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/location")
class RoutingController(
    private val mapService: MapService,
    private val mapboxRateLimiter: MapboxRateLimiter,
) {

    @GetMapping("/geocode")
    fun getCoordinates(
        @CurrentUser actor: AuthUser,
        @RequestParam address: String,
        @RequestParam(required = false) proximityLng: Double?,
        @RequestParam(required = false) proximityLat: Double?,
    ): ApiResponse<String?> {
        mapboxRateLimiter.check(actor, "geocode")
        if ((proximityLng == null) != (proximityLat == null)) {
            throw BadRequestException(message = "proximityLng và proximityLat phải được truyền cùng nhau")
        }
        if (proximityLng != null && (!proximityLng.isFinite() || proximityLng !in -180.0..180.0)) {
            throw BadRequestException(message = "proximityLng phải nằm trong khoảng -180 đến 180")
        }
        if (proximityLat != null && (!proximityLat.isFinite() || proximityLat !in -90.0..90.0)) {
            throw BadRequestException(message = "proximityLat phải nằm trong khoảng -90 đến 90")
        }
        val proximity = if (proximityLng != null && proximityLat != null) {
            GeoPoint(lat = proximityLat, lng = proximityLng)
        } else null
        val result = mapService.getCoordinatesFromAddress(address, proximity)
        return ApiResponse.ok(result)
    }

    @GetMapping("/directions")
    fun getDirections(
        @CurrentUser actor: AuthUser,
        @RequestParam origin: String,
        @RequestParam destination: String,
    ): ApiResponse<Route> {
        mapboxRateLimiter.check(actor, "directions")
        val result = mapService.getDistanceAndDuration(origin, destination)
        return ApiResponse.ok(result)
    }
}
