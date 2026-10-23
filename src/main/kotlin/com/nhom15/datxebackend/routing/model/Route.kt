package com.nhom15.datxebackend.routing.model

import com.nhom15.datxebackend.common.domain.GeoPoint

data class Route(
    val distance: Double,
    val duration: Double,
    val detailedJson: String,
    val coordinates: List<GeoPoint> = emptyList(),
)
