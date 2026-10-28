package com.nhom15.datxebackend.routing.model

import com.nhom15.datxebackend.common.domain.GeoPoint

data class PlaceSuggestion(
    val id: String,
    val name: String,
    val address: String,
    val provider: String,
    val coordinate: GeoPoint? = null,
    val secondaryAddress: String? = null,
)
data class PlaceSearchResult(val provider: String, val limitedCoverage: Boolean, val places: List<PlaceSuggestion>)
