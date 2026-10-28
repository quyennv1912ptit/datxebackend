package com.nhom15.datxebackend.routing.service

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.routing.model.PlaceSuggestion
import tools.jackson.databind.JsonNode
import java.text.Normalizer
import java.util.Locale

internal object PlaceSearchParsing {
    private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace('đ', 'd')
    private fun tokens(text: String) = Regex("[a-z0-9]+(?:/[a-z0-9]+)*").findAll(normalized(text)).map { it.value }.toList()

    // Require every typed word/house number; never turn “Hồ Tây” into “Phố Tây Sơn”.
    fun matches(query: String, candidate: String): Boolean {
        val requested = tokens(query)
        val available = tokens(candidate)
        return requested.isNotEmpty() && requested.withIndex().all { (index, word) ->
            available.any { it == word || (index == requested.lastIndex && word.none(Char::isDigit) && it.startsWith(word)) }
        }
    }
    fun text(node: JsonNode, name: String): String = node.path(name).asString("").trim()
    fun coordinate(node: JsonNode): GeoPoint? {
        val lat = node.path("lat").asDouble(Double.NaN)
        val lng = node.path("lng").asDouble(Double.NaN)
        return if (lat.isFinite() && lat in -90.0..90.0 && lng.isFinite() && lng in -180.0..180.0) GeoPoint(lat, lng) else null
    }
    fun mapbox(feature: JsonNode): PlaceSuggestion? {
        val coordinates = feature.path("geometry").path("coordinates")
        val lng = coordinates.path(0).asDouble(Double.NaN)
        val lat = coordinates.path(1).asDouble(Double.NaN)
        val address = text(feature, "place_name")
        if (!lat.isFinite() || lat !in -90.0..90.0 || !lng.isFinite() || lng !in -180.0..180.0 || address.isBlank()) return null
        val street = text(feature, "text")
        val number = text(feature, "address")
        val name = if (number.isNotBlank() && !street.startsWith(number)) "$number $street" else street
        return PlaceSuggestion(text(feature, "id").ifBlank { "$lng,$lat" }, name.ifBlank { address.substringBefore(',') }, address, "mapbox", GeoPoint(lat, lng))
    }
}
