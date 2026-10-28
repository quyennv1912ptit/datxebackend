package com.nhom15.datxebackend.routing.model

import com.nhom15.datxebackend.common.domain.GeoPoint

data class DrivingRoute(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val responseBody: String = "",
    val coordinates: List<GeoPoint> = emptyList(),
    val provider: String = "mapbox",
    val limitation: String? = null,
)
