package com.nhom15.datxebackend.tracking.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.port.NearbyDriver
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.tracking.service.LocationService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import kotlin.math.round

@RestController
@RequestMapping("/api/location")
class NearbyDriversController(
    private val locationService: LocationService,
    private val rateLimiter: RedisRateLimiter,
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

    @GetMapping("/customer/nearby-drivers")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun findNearbyDriversForCustomer(
        @CurrentUser actor: AuthUser,
        @RequestParam longitude: Double,
        @RequestParam latitude: Double,
    ): ApiResponse<List<CustomerNearbyDriverMarker>> {
        if (!longitude.isFinite() || longitude !in -180.0..180.0 ||
            !latitude.isFinite() || latitude !in -85.05112878..85.05112878
        ) {
            throw AppException(ErrorCode.VALIDATION_ERROR, "Tọa độ vị trí không hợp lệ")
        }

        rateLimiter.check("rate:customer-nearby-drivers:${actor.id}", 15, Duration.ofMinutes(1))
        val center = GeoPoint(lat = latitude, lng = longitude)
        val markers = locationService.findNearbyDrivers(center, CUSTOMER_RADIUS_KM, CUSTOMER_MAX_RESULTS)
            .map { nearby ->
                CustomerNearbyDriverMarker(
                    location = GeoPoint(
                        lat = round(nearby.location.lat * COORDINATE_PRECISION) / COORDINATE_PRECISION,
                        lng = round(nearby.location.lng * COORDINATE_PRECISION) / COORDINATE_PRECISION,
                    ),
                )
            }
        return ApiResponse.ok(markers)
    }

    private companion object {
        const val CUSTOMER_RADIUS_KM = 3.0
        const val CUSTOMER_MAX_RESULTS = 30L
        const val COORDINATE_PRECISION = 1_000.0
    }
}

data class CustomerNearbyDriverMarker(
    val location: GeoPoint,
)
