package com.nhom15.datxebackend.routing.service

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.config.properties.MapboxProperties
import com.nhom15.datxebackend.routing.api.DrivingRouteProvider
import com.nhom15.datxebackend.routing.model.Route
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Duration
import java.util.Locale
import kotlin.math.round

@Service
class MapService(
    private val restClient: RestClient,
    private val properties: MapboxProperties,
    private val drivingRouteProvider: DrivingRouteProvider,
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getCoordinatesFromAddress(address: String, proximity: GeoPoint? = null): String? {
        val reverse = address.split(',').let { it.size == 2 && it.all { part -> part.trim().toDoubleOrNull() != null } }
        val normalizedAddress = normalizeAddress(address)
        val proximityKey = proximity?.rounded()?.let { "${it.lat},${it.lng}" } ?: "-"
        val cacheKey = RedisKeys.mapboxGeocodeCache(sha256("strict-v2|$normalizedAddress|$proximityKey"))
        getStringCache(cacheKey)?.let { return it }

        return try {
            val response = restClient.get()
                .uri { builder ->
                    val uri = builder
                        .path("/geocoding/v5/mapbox.places")
                        .pathSegment("$address.json")
                        .queryParam("access_token", properties.token.trim())
                        .queryParam("country", "vn")
                        .queryParam("language", "vi")
                    if (!reverse) uri.queryParam("autocomplete", "true")
                        .queryParam("fuzzyMatch", "false")
                        .queryParam("limit", "10")
                    proximity?.let { uri.queryParam("proximity", "${it.lng},${it.lat}") }
                    uri.build()
                }
                .retrieve()
                .body(String::class.java)
            response?.let { setStringCache(cacheKey, it, GEOCODE_CACHE_TTL) }
            response
        } catch (exception: Exception) {
            throw AppException(ErrorCode.ROUTE_UNAVAILABLE, cause = exception)
        }
    }

    fun getDistanceAndDuration(origin: String, destination: String): Route {
        val pickup = origin.toGeoPoint().rounded()
        val dropoff = destination.toGeoPoint().rounded()
        val cacheKey = RedisKeys.mapboxDirectionsCache(
            sha256("${properties.profile}|${pickup.lat},${pickup.lng}|${dropoff.lat},${dropoff.lng}"),
        )
        getRouteCache(cacheKey)?.let { return it }

        return try {
            val route = drivingRouteProvider.findRoute(
                pickup = pickup,
                dropoff = dropoff,
                includeGeometry = true,
            )
            Route(route.distanceMeters, route.durationSeconds, route.responseBody, route.coordinates).also {
                setRouteCache(cacheKey, it)
            }
        } catch (exception: Exception) {
            if (exception is AppException) throw exception
            throw AppException(ErrorCode.ROUTE_UNAVAILABLE, cause = exception)
        }
    }

    private fun getStringCache(key: String): String? = runCatching {
        redis.opsForValue().get(key)
    }.onFailure {
        log.debug("Không đọc được cache Mapbox")
    }.getOrNull()

    private fun setStringCache(key: String, value: String, ttl: Duration) {
        runCatching { redis.opsForValue().set(key, value, ttl) }
            .onFailure { log.debug("Không ghi được cache Mapbox") }
    }

    private fun getRouteCache(key: String): Route? {
        val json = getStringCache(key) ?: return null
        return runCatching { objectMapper.readValue(json, Route::class.java) }
            .onFailure { log.debug("Không đọc được cache directions") }
            .getOrNull()
    }

    private fun setRouteCache(key: String, route: Route) {
        runCatching { objectMapper.writeValueAsString(route) }
            .onSuccess { setStringCache(key, it, DIRECTIONS_CACHE_TTL) }
            .onFailure { log.debug("Không serialize được cache directions") }
    }

    private fun normalizeAddress(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE, " ")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun GeoPoint.rounded(): GeoPoint = GeoPoint(
        lat = round4(lat),
        lng = round4(lng),
    )

    private fun round4(value: Double): Double {
        val rounded = round(value * COORDINATE_SCALE) / COORDINATE_SCALE
        return if (rounded == 0.0) 0.0 else rounded
    }

    private fun String.toGeoPoint(): GeoPoint {
        val parts = split(',')
        if (parts.size != 2) throw AppException(ErrorCode.ROUTE_UNAVAILABLE)
        val lng = parts[0].toDoubleOrNull() ?: throw AppException(ErrorCode.ROUTE_UNAVAILABLE)
        val lat = parts[1].toDoubleOrNull() ?: throw AppException(ErrorCode.ROUTE_UNAVAILABLE)
        if (!lng.isFinite() || lng !in -180.0..180.0 || !lat.isFinite() || lat !in -90.0..90.0) {
            throw AppException(ErrorCode.ROUTE_UNAVAILABLE)
        }
        return GeoPoint(lat = lat, lng = lng)
    }

    private companion object {
        val GEOCODE_CACHE_TTL: Duration = Duration.ofHours(24)
        val DIRECTIONS_CACHE_TTL: Duration = Duration.ofMinutes(5)
        const val COORDINATE_SCALE = 10_000.0
        val WHITESPACE = Regex("\\s+")
    }
}
