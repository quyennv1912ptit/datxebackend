package com.nhom15.datxebackend.routing.service

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.util.GeoUtils.distanceKm
import com.nhom15.datxebackend.config.properties.GoongProperties
import com.nhom15.datxebackend.routing.model.NearbyPlace
import com.nhom15.datxebackend.routing.model.NearbyPlacesResult
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import kotlin.math.roundToInt

/**
 * Điểm quanh ghim bản đồ.
 *
 * Goong Nearby Search không lọc cứng theo bán kính (tham số radius chỉ là gợi ý),
 * nên backend luôn đo lại khoảng cách từ tâm ghim, sắp xếp gần → xa rồi mới cắt
 * theo bán kính mong muốn. Nếu khu vực quá thưa dữ liệu, trả về các điểm gần nhất
 * kèm cờ `withinRadius = false` để app hiển thị trung thực.
 */
@Service
class PlaceNearbyService(
    private val goong: RestClient,
    private val properties: GoongProperties,
    private val mapper: ObjectMapper,
) {
    private val cache = PlaceQueryCache<NearbyPlacesResult>(Duration.ofMinutes(2))

    fun nearby(point: GeoPoint, radiusMeters: Int, limit: Int): NearbyPlacesResult {
        if (properties.apiKey.isBlank()) return empty(radiusMeters)
        val key = "${quantize(point.lat, 4)},${quantize(point.lng, 4)}|$radiusMeters|$limit"
        cache.get(key)?.let { return it }
        // Nearby Search là dữ liệu phụ: lỗi nhà cung cấp không được chặn người dùng
        // xác nhận vị trí ghim, nên luôn trả về danh sách rỗng thay vì ném lỗi.
        val result = runCatching { fetch(point, radiusMeters, limit) }.getOrNull()
            ?: return empty(radiusMeters)
        cache.put(key, result)
        return result
    }

    private fun fetch(point: GeoPoint, radiusMeters: Int, limit: Int): NearbyPlacesResult {
        val payload = mapper.writeValueAsString(
            mapOf(
                "includedTypes" to INCLUDED_TYPES,
                "maxResultCount" to MAX_PROVIDER_RESULTS,
                "locationRestriction" to mapOf(
                    "circle" to mapOf(
                        "center" to mapOf("latitude" to point.lat, "longitude" to point.lng),
                        "radius" to radiusMeters.toDouble(),
                    ),
                ),
                "rankPreference" to "DISTANCE",
            ),
        )
        val body = goong.post()
            .uri { builder -> builder.path("/v2/place/nearbysearch").queryParam("api_key", properties.apiKey.trim()).build() }
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .body(String::class.java) ?: return empty(radiusMeters)

        val candidates = mapper.readTree(body).path("results").mapNotNull { node ->
            val id = PlaceSearchParsing.text(node, "place_id")
            val address = PlaceSearchParsing.text(node, "formatted_address")
                .ifBlank { PlaceSearchParsing.text(node, "address") }
            val coordinate = PlaceSearchParsing.coordinate(node.path("geometry").path("location"))
            if (id.isBlank() || address.isBlank() || coordinate == null) return@mapNotNull null
            val name = displayName(PlaceSearchParsing.text(node, "name"), address)
            if (name.length < 2) return@mapNotNull null
            NearbyPlace(
                id = id,
                name = name,
                address = address,
                provider = "goong",
                coordinate = coordinate,
                distanceMeters = meters(point, coordinate),
            )
        }
            .distinctBy { "${it.provider}:${it.id}" }
            .distinctBy { "${it.address.lowercase()}|${it.coordinate.lat},${it.coordinate.lng}" }
            .sortedBy { it.distanceMeters }

        val withinRadius = candidates.filter { it.distanceMeters <= radiusMeters }.take(limit)
        val places = withinRadius.ifEmpty { candidates.take(limit) }
        return NearbyPlacesResult(
            provider = "goong",
            radiusMeters = radiusMeters,
            withinRadius = withinRadius.isNotEmpty(),
            places = places,
        )
    }

    private fun empty(radiusMeters: Int) = NearbyPlacesResult(
        provider = "goong",
        radiusMeters = radiusMeters,
        withinRadius = true,
        places = emptyList(),
    )

    private fun meters(origin: GeoPoint, target: GeoPoint) =
        (distanceKm(origin, target) * 1_000).roundToInt()

    /** Goong đôi khi trả tên chỉ là số nhà ("4", "9"); khi đó ghép thêm đoạn đường. */
    private fun displayName(rawName: String, address: String): String {
        val name = rawName.trim()
        if (name.length >= 2 && name.any { it.isLetter() }) return name
        val parts = address.split(',').map(String::trim).filter(String::isNotEmpty)
        return when {
            name.isNotEmpty() && parts.size > 1 -> "$name, ${parts[1]}".take(60)
            parts.size > 1 -> parts.take(2).joinToString(", ")
            else -> name
        }
    }

    private companion object {
        const val MAX_PROVIDER_RESULTS = 20

        // Danh sách này đã được kiểm chứng với Goong Nearby Search V2; thêm type
        // không tồn tại có thể làm nhà cung cấp trả 400 cho cả request.
        val INCLUDED_TYPES = listOf(
            "point_of_interest",
            "establishment",
            "store",
            "street_address",
            "premise",
            "subpremise",
            "beauty_salon",
            "restaurant",
            "cafe",
            "lodging",
        )
    }
}
