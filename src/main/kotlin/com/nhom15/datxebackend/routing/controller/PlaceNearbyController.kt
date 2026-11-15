package com.nhom15.datxebackend.routing.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.routing.model.NearbyPlacesResult
import com.nhom15.datxebackend.routing.service.LocationRateLimiter
import com.nhom15.datxebackend.routing.service.PlaceNearbyService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/location/places")
class PlaceNearbyController(
    private val service: PlaceNearbyService,
    private val limiter: LocationRateLimiter,
) {
    @GetMapping("/nearby")
    fun nearby(
        @CurrentUser actor: AuthUser,
        @RequestParam lat: Double,
        @RequestParam lng: Double,
        @RequestParam(defaultValue = "100") radius: Int,
        @RequestParam(defaultValue = "8") limit: Int,
    ): ApiResponse<NearbyPlacesResult> {
        if (radius !in 10..2_000) throw BadRequestException(message = "Bán kính phải từ 10 đến 2000 mét")
        if (limit !in 1..20) throw BadRequestException(message = "Số điểm gần đây phải từ 1 đến 20")
        val point = point(lat, lng)
        limiter.check(actor, "places-nearby")
        return ApiResponse.ok(service.nearby(point, radius, limit))
    }

    private fun point(lat: Double, lng: Double): GeoPoint {
        if (!lat.isFinite() || lat !in -90.0..90.0 || !lng.isFinite() || lng !in -180.0..180.0) {
            throw BadRequestException(message = "Tọa độ không hợp lệ")
        }
        return GeoPoint(lat, lng)
    }
}
