package com.nhom15.datxebackend.customer.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.customer.dto.CustomerPlaceResponse
import com.nhom15.datxebackend.customer.dto.ReportPlaceRequest
import com.nhom15.datxebackend.customer.dto.ReportPlaceResponse
import com.nhom15.datxebackend.customer.service.CustomerPlaceService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/customer/places")
@PreAuthorize("hasRole('CUSTOMER')")
class CustomerPlaceController(private val service: CustomerPlaceService) {
    @GetMapping("/recent")
    fun recent(
        @CurrentUser actor: AuthUser,
        @RequestParam(defaultValue = "dropoff") kind: String,
        @RequestParam(required = false) proximityLat: Double?,
        @RequestParam(required = false) proximityLng: Double?,
        @RequestParam(defaultValue = "5") limit: Int,
    ): ApiResponse<List<CustomerPlaceResponse>> {
        if (kind !in setOf("pickup", "dropoff")) throw BadRequestException(message = "Loại địa điểm không hợp lệ")
        if (limit !in 1..20) throw BadRequestException(message = "Số địa điểm phải từ 1 đến 20")
        return ApiResponse.ok(service.recent(actor, kind, proximity(proximityLat, proximityLng), limit))
    }

    @GetMapping("/popular")
    fun popular(
        @CurrentUser actor: AuthUser,
        @RequestParam(required = false) proximityLat: Double?,
        @RequestParam(required = false) proximityLng: Double?,
        @RequestParam(defaultValue = "6") limit: Int,
    ): ApiResponse<List<CustomerPlaceResponse>> {
        if (limit !in 1..20) throw BadRequestException(message = "Số địa điểm phải từ 1 đến 20")
        return ApiResponse.ok(service.popular(proximity(proximityLat, proximityLng), limit))
    }

    @PostMapping("/report")
    fun report(
        @CurrentUser actor: AuthUser,
        @Valid @RequestBody request: ReportPlaceRequest,
    ): ApiResponse<ReportPlaceResponse> = ApiResponse.ok(service.report(actor, request))

    private fun proximity(lat: Double?, lng: Double?): GeoPoint? {
        if ((lat == null) != (lng == null)) throw BadRequestException(message = "Cần truyền đủ vĩ độ và kinh độ")
        if (lat == null || lng == null) return null
        if (!lat.isFinite() || lat !in -90.0..90.0 || !lng.isFinite() || lng !in -180.0..180.0) {
            throw BadRequestException(message = "Tọa độ không hợp lệ")
        }
        return GeoPoint(lat, lng)
    }
}
