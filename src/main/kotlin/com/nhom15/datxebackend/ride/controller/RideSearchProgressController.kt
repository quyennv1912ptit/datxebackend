package com.nhom15.datxebackend.ride.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.ForbiddenException
import java.time.Instant
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.ride.service.RideQueryService
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

data class RideSearchProgress(val round: Int, val radiusKm: Double, val maxRadiusKm: Double)

data class RideDriverFix(val location: GeoPoint, val timestamp: Instant, val accuracy: Double? = null)

@RestController
class RideSearchProgressController(
    private val rides: RideQueryService,
    private val redis: StringRedisTemplate,
    private val matching: MatchingProperties,
) {
    @GetMapping("/api/customer/rides/{id}/search-progress")
    fun progress(@CurrentUser actor: AuthUser, @PathVariable id: Long): ApiResponse<RideSearchProgress?> {
        if (actor.role != Role.CUSTOMER) throw ForbiddenException()
        val ride = rides.getDetail(actor, id)
        if (ride.status.name != "SEARCHING") return ApiResponse.ok(null)
        val fields = redis.opsForHash<String, String>().multiGet(
            RedisKeys.rideState(id.toString()), listOf("status", "nextRoundIndex"),
        )
        val round = fields.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
        if (fields.firstOrNull() != "SEARCHING" || round == null) return ApiResponse.ok(null)
        val radii = matching.searchRadiiKm
        return ApiResponse.ok(RideSearchProgress(round, radii[(round - 1).coerceAtMost(radii.lastIndex)], radii.max()))
    }

    @GetMapping("/api/customer/rides/{id}/approach-location")
    fun location(@CurrentUser actor: AuthUser, @PathVariable id: Long): ApiResponse<RideDriverFix?> {
        if (actor.role != Role.CUSTOMER) throw ForbiddenException()
        val ride = rides.getDetail(actor, id)
        if (ride.status.name !in setOf("DRIVER_ASSIGNED", "DRIVER_ARRIVING")) return ApiResponse.ok(null)
        val driverId = ride.driverId ?: return ApiResponse.ok(null)
        val fields = redis.opsForHash<String, String>().multiGet(
            RedisKeys.driverLocation(driverId.toString()), listOf("lat", "lng", "sampledAtMs"),
        )
        val lat = fields.getOrNull(0)?.toDoubleOrNull() ?: return ApiResponse.ok(null)
        val lng = fields.getOrNull(1)?.toDoubleOrNull() ?: return ApiResponse.ok(null)
        val timestamp = fields.getOrNull(2)?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return ApiResponse.ok(null)
        if (timestamp.plus(matching.driverAliveTtl).isBefore(Instant.now())) return ApiResponse.ok(null)
        return ApiResponse.ok(RideDriverFix(GeoPoint(lat = lat, lng = lng), timestamp))
    }
}
