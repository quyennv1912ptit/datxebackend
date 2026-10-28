package com.nhom15.datxebackend.routing.model

import com.nhom15.datxebackend.common.enums.VehicleType
import java.time.Instant

data class SavedRideRoute(
    val id: String,
    val polyline: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val provider: String,
    val vehicleType: VehicleType,
    val limitation: String? = null,
    val expiresAt: Instant? = null,
) {
    val polylinePrecision: Int get() = 6
    val coordinates get() = PolylineCodec.decode(polyline, 6)
    fun drivingRoute() = DrivingRoute(distanceMeters, durationSeconds, coordinates = coordinates, provider = provider, limitation = limitation)
}
