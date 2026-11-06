package com.nhom15.datxebackend.routing.service

import com.nhom15.datxebackend.common.domain.GeoPoint
import tools.jackson.databind.JsonNode

internal object PlaceSearchParsing {
    fun text(node: JsonNode, name: String): String = node.path(name).asString("").trim()
    fun coordinate(node: JsonNode): GeoPoint? {
        val lat = node.path("lat").asDouble(Double.NaN)
        val lng = node.path("lng").asDouble(Double.NaN)
        return if (lat.isFinite() && lat in -90.0..90.0 && lng.isFinite() && lng in -180.0..180.0) GeoPoint(lat, lng) else null
    }
}
