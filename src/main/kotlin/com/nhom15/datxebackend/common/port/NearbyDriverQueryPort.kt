package com.nhom15.datxebackend.common.port

import com.nhom15.datxebackend.common.domain.GeoPoint

interface NearbyDriverQueryPort {
    fun findNearbyDrivers(center: GeoPoint, radiusKm: Double, maxResults: Long): List<NearbyDriver>

    fun findNearbyDrivers(center: GeoPoint, radiusKm: Double): List<NearbyDriver> =
        findNearbyDrivers(center, radiusKm, DEFAULT_MAX_RESULTS)

    private companion object {
        const val DEFAULT_MAX_RESULTS = 20L
    }
}

data class NearbyDriver(
    val driverId: Long,
    val distanceKm: Double,
    val location: GeoPoint,
)
