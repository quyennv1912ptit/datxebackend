package com.nhom15.datxebackend.routing.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.routing.model.PlaceSearchResult
import com.nhom15.datxebackend.routing.model.PlaceSuggestion
import com.nhom15.datxebackend.routing.service.MapboxRateLimiter
import com.nhom15.datxebackend.routing.service.PlaceSearchService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/location/places")
class PlaceSearchController(private val service: PlaceSearchService, private val limiter: MapboxRateLimiter) {
    @GetMapping("/search")
    fun search(@CurrentUser actor: AuthUser, @RequestParam query: String,
        @RequestParam(required = false) proximityLat: Double?, @RequestParam(required = false) proximityLng: Double?): ApiResponse<PlaceSearchResult> {
        val input = query.trim()
        if (input.length !in 2..255) throw BadRequestException(message = "Nhập địa chỉ từ 2 đến 255 ký tự")
        if ((proximityLat == null) != (proximityLng == null)) throw BadRequestException(message = "Cần truyền đủ vĩ độ và kinh độ")
        val proximity = if (proximityLat != null && proximityLng != null) point(proximityLat, proximityLng) else null
        limiter.check(actor, "places-search")
        return ApiResponse.ok(service.search(input, proximity))
    }
    @GetMapping("/detail")
    fun detail(@CurrentUser actor: AuthUser, @RequestParam id: String): ApiResponse<PlaceSuggestion> {
        if (id.isBlank() || id.length > 2048) throw BadRequestException(message = "Địa điểm không hợp lệ")
        limiter.check(actor, "places-detail")
        return ApiResponse.ok(service.detail(id))
    }
    @GetMapping("/reverse")
    fun reverse(@CurrentUser actor: AuthUser, @RequestParam lat: Double, @RequestParam lng: Double): ApiResponse<PlaceSuggestion> {
        val location = point(lat, lng)
        limiter.check(actor, "places-reverse")
        return ApiResponse.ok(service.reverse(location))
    }
    private fun point(lat: Double, lng: Double): GeoPoint {
        if (!lat.isFinite() || lat !in -90.0..90.0 || !lng.isFinite() || lng !in -180.0..180.0) throw BadRequestException(message = "Tọa độ không hợp lệ")
        return GeoPoint(lat, lng)
    }
}
