package com.nhom15.datxebackend.routing.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.config.properties.PlaceSearchProperties
import com.nhom15.datxebackend.routing.model.PlaceSearchResult
import com.nhom15.datxebackend.routing.model.PlaceSuggestion
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Service
class PlaceSearchService(
    private val maps: MapService,
    @param:Qualifier("goongPlacesClient") private val goong: RestClient,
    private val properties: PlaceSearchProperties,
    private val mapper: ObjectMapper,
) {
    fun search(query: String, proximity: GeoPoint?): PlaceSearchResult = safely {
        if (!properties.useGoong) {
            val root = mapper.readTree(maps.getCoordinatesFromAddress(query, proximity) ?: "{}")
            val places = root.path("features").filter { it.path("relevance").asDouble(1.0) >= 0.75 }
                .mapNotNull { PlaceSearchParsing.mapbox(it) }
                .filter { PlaceSearchParsing.matches(query, it.address) }
                .distinctBy { it.id }
            PlaceSearchResult("mapbox", true, places)
        } else {
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
    }

    fun detail(id: String): PlaceSuggestion = safely {
        if (!properties.useGoong) throw BadRequestException(message = "Nguồn tìm địa điểm đã thay đổi. Vui lòng tìm lại địa chỉ.")
        val root = call("/v2/place/detail", mapOf("place_id" to id, "has_deprecated_administrative_unit" to "true"))
        val node = root.path("result")
        val address = PlaceSearchParsing.text(node, "formatted_address")
        val coordinate = PlaceSearchParsing.coordinate(node.path("geometry").path("location"))
        if (address.isBlank() || coordinate == null) throw AppException(ErrorCode.LOCATION_SEARCH_UNAVAILABLE)
        PlaceSuggestion(id, PlaceSearchParsing.text(node, "name").ifBlank { address.substringBefore(',') }, address, "goong", coordinate,
            PlaceSearchParsing.text(node, "deprecated_description").takeIf { it.isNotBlank() && it != address })
    }

    fun reverse(point: GeoPoint): PlaceSuggestion = safely {
        if (!properties.useGoong) {
            val root = mapper.readTree(maps.getCoordinatesFromAddress("${point.lng},${point.lat}") ?: "{}")
            val place = root.path("features").firstNotNullOfOrNull { PlaceSearchParsing.mapbox(it) }
                ?: throw AppException(ErrorCode.LOCATION_SEARCH_UNAVAILABLE)
            place.copy(id = "pin:${point.lng},${point.lat}", coordinate = point)
        } else {
            val root = call("/v2/geocode", mapOf("latlng" to "${point.lat},${point.lng}", "has_deprecated_administrative_unit" to "true"))
            val node = root.path("results").firstOrNull() ?: throw AppException(ErrorCode.LOCATION_SEARCH_UNAVAILABLE)
            val address = PlaceSearchParsing.text(node, "formatted_address")
            if (address.isBlank()) throw AppException(ErrorCode.LOCATION_SEARCH_UNAVAILABLE)
            PlaceSuggestion("pin:${point.lng},${point.lat}", PlaceSearchParsing.text(node, "name").ifBlank { address.substringBefore(',') }, address, "goong", point,
                PlaceSearchParsing.text(node, "deprecated_description").takeIf { it.isNotBlank() && it != address })
        }
    }

    private fun call(path: String, params: Map<String, String>): JsonNode {
        val body = goong.get().uri { builder ->
            builder.path(path).queryParam("api_key", properties.goongApiKey.trim())
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
