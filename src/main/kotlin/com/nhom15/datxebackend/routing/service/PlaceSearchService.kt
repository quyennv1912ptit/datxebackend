package com.nhom15.datxebackend.routing.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.util.GeoUtils.distanceKm
import com.nhom15.datxebackend.config.properties.GoongProperties
import com.nhom15.datxebackend.routing.model.PlaceSearchResult
import com.nhom15.datxebackend.routing.model.PlaceSuggestion
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.time.Duration
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Service
class PlaceSearchService(
    private val goong: RestClient,
    private val properties: GoongProperties,
    private val mapper: ObjectMapper,
) {
    // Goong tính phí theo lượt gọi: cache ngắn hạn để gộp các truy vấn trùng nhau.
    private val searchCache = PlaceQueryCache<PlaceSearchResult>(Duration.ofMinutes(10))
    private val detailCache = PlaceQueryCache<PlaceSuggestion>(Duration.ofMinutes(30))
    private val geocodeCache = PlaceQueryCache<String>(Duration.ofMinutes(30))
    private val reverseCache = PlaceQueryCache<PlaceSuggestion>(Duration.ofMinutes(30))

    fun search(query: String, proximity: GeoPoint?): PlaceSearchResult {
        val key = buildString {
            append(normalizeQuery(query))
            append('|')
            append(proximity?.let { "${quantize(it.lat, 2)},${quantize(it.lng, 2)}" } ?: "-")
        }
        searchCache.get(key)?.let { return it }
        val result = searchUncached(query, proximity)
        searchCache.put(key, result)
        return result
    }

    private fun searchUncached(query: String, proximity: GeoPoint?): PlaceSearchResult = safely {
        val root = call("/v2/place/autocomplete", buildMap {
            put("input", query); put("limit", "10"); put("more_compound", "true")
            put("has_deprecated_administrative_unit", "true")
            proximity?.let { put("location", "${it.lat},${it.lng}"); put("radius", "2000") }
        })
        val places = root.path("predictions").mapNotNull { node ->
            val id = PlaceSearchParsing.text(node, "place_id")
            val address = PlaceSearchParsing.text(node, "description")
            if (id.isBlank() || address.isBlank()) null else PlaceSuggestion(
                id, PlaceSearchParsing.text(node.path("structured_formatting"), "main_text").ifBlank { address.substringBefore(',') },
                address, "goong", secondaryAddress = PlaceSearchParsing.text(node, "deprecated_description").takeIf { it.isNotBlank() && it != address },
            )
        }.distinctBy { it.id }
        PlaceSearchResult("goong", false, places)
    }

    fun detail(id: String): PlaceSuggestion {
        detailCache.get(id)?.let { return it }
        val result = detailUncached(id)
        detailCache.put(id, result)
        return result
    }

    private fun detailUncached(id: String): PlaceSuggestion = safely {
        val root = call("/v2/place/detail", mapOf("place_id" to id, "has_deprecated_administrative_unit" to "true"))
        val node = root.path("result")
        val address = PlaceSearchParsing.text(node, "formatted_address")
        val coordinate = PlaceSearchParsing.coordinate(node.path("geometry").path("location"))
        if (address.isBlank() || coordinate == null) throw AppException(ErrorCode.LOCATION_SEARCH_UNAVAILABLE)
        PlaceSuggestion(id, PlaceSearchParsing.text(node, "name").ifBlank { address.substringBefore(',') }, address, "goong", coordinate,
            PlaceSearchParsing.text(node, "deprecated_description").takeIf { it.isNotBlank() && it != address })
    }

    fun reverse(point: GeoPoint): PlaceSuggestion {
        val key = "${quantize(point.lat, 4)},${quantize(point.lng, 4)}"
        reverseCache.get(key)?.let { return it.copy(id = "pin:${point.lng},${point.lat}", coordinate = point) }
        val result = reverseUncached(point)
        reverseCache.put(key, result)
        return result
    }

    private fun reverseUncached(point: GeoPoint): PlaceSuggestion = safely {
        val root = call("/v2/geocode", mapOf("latlng" to "${point.lat},${point.lng}", "has_deprecated_administrative_unit" to "true"))
        val node = root.path("results").firstOrNull() ?: throw AppException(ErrorCode.LOCATION_SEARCH_UNAVAILABLE)
        val address = PlaceSearchParsing.text(node, "formatted_address")
        if (address.isBlank()) throw AppException(ErrorCode.LOCATION_SEARCH_UNAVAILABLE)
        PlaceSuggestion("pin:${point.lng},${point.lat}", PlaceSearchParsing.text(node, "name").ifBlank { address.substringBefore(',') }, address, "goong", point,
            PlaceSearchParsing.text(node, "deprecated_description").takeIf { it.isNotBlank() && it != address })
    }

    // Retain the legacy /geocode FeatureCollection contract for deployed clients.
    fun geocode(address: String, proximity: GeoPoint?): String {
        val key = "${normalizeQuery(address)}|${proximity?.let { "${it.lat},${it.lng}" } ?: "-"}"
        geocodeCache.get(key)?.let { return it }
        return geocodeUncached(address, proximity).also { geocodeCache.put(key, it) }
    }

    private fun geocodeUncached(address: String, proximity: GeoPoint?): String = safely {
        val parts = address.split(',').map { it.trim().toDoubleOrNull() }
        val point = if (parts.size == 2) {
            val lng = parts[0]
            val lat = parts[1]
            if (lng != null && lat != null && lng in -180.0..180.0 && lat in -90.0..90.0) GeoPoint(lat, lng) else null
        } else null
        val params = if (point != null) mapOf("latlng" to "${point.lat},${point.lng}") else mapOf("address" to address)
        val results = call("/v2/geocode", params).path("results").mapNotNull { node ->
            val coordinate = PlaceSearchParsing.coordinate(node.path("geometry").path("location")) ?: return@mapNotNull null
            val label = PlaceSearchParsing.text(node, "formatted_address").ifBlank { return@mapNotNull null }
            PlaceSuggestion(
                PlaceSearchParsing.text(node, "place_id").ifBlank { "${coordinate.lng},${coordinate.lat}" },
                PlaceSearchParsing.text(node, "name").ifBlank { label.substringBefore(',') },
                label, "goong", coordinate,
            )
        }
        val features = (if (proximity == null) results else results.sortedBy {
            val coordinate = it.coordinate!!
            distanceKm(proximity, coordinate)
        }).map {
            val coordinate = it.coordinate!!
            mapOf(
                "type" to "Feature", "id" to it.id, "text" to it.name, "place_name" to it.address,
                "geometry" to mapOf("type" to "Point", "coordinates" to listOf(coordinate.lng, coordinate.lat)),
            )
        }
        mapper.writeValueAsString(mapOf("type" to "FeatureCollection", "features" to features))
    }

    private fun call(path: String, params: Map<String, String>): JsonNode {
        require(properties.apiKey.isNotBlank())
        val body = goong.get().uri { builder ->
            builder.path(path).queryParam("api_key", properties.apiKey.trim())
            params.forEach { (key, value) -> builder.queryParam(key, "{${key}}") }
            builder.build(params)
        }.retrieve().body(String::class.java) ?: throw AppException(ErrorCode.LOCATION_SEARCH_UNAVAILABLE)
        val root = mapper.readTree(body)
        if (PlaceSearchParsing.text(root, "status") !in setOf("OK", "ZERO_RESULTS")) throw AppException(ErrorCode.LOCATION_SEARCH_UNAVAILABLE)
        return root
    }
    private fun <T> safely(action: () -> T): T = try { action() } catch (error: AppException) { throw error } catch (_: Exception) {
        // Do not propagate provider URLs, API keys, or raw upstream bodies into app errors/logs.
        throw AppException(ErrorCode.LOCATION_SEARCH_UNAVAILABLE)
    }
}
