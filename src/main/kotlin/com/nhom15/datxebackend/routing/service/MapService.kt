package com.nhom15.datxebackend.routing.service

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.routing.api.DrivingRouteProvider
import com.nhom15.datxebackend.routing.model.Route
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

@Service
class MapService(
    private val drivingRouteProvider: DrivingRouteProvider,
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getDistanceAndDuration(origin: String, destination: String, vehicleType: VehicleType = VehicleType.CAR): Route {
        val pickup = origin.toGeoPoint()
        val dropoff = destination.toGeoPoint()
        val cacheKey = RedisKeys.goongDirectionsCache(
            sha256("exact-v1|$vehicleType|${pickup.lat},${pickup.lng}|${dropoff.lat},${dropoff.lng}"),
        )
        getRouteCache(cacheKey)?.let { return it }

        return try {
            val route = drivingRouteProvider.findRoute(
                pickup = pickup,
                dropoff = dropoff,
                vehicleType = vehicleType,
                includeGeometry = true,
            )
            Route(route.distanceMeters, route.durationSeconds, route.responseBody, route.coordinates, route.steps).also {
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
        log.debug("Không đọc được cache Goong")
    }.getOrNull()

    private fun setStringCache(key: String, value: String, ttl: Duration) {
        runCatching { redis.opsForValue().set(key, value, ttl) }
            .onFailure { log.debug("Không ghi được cache Goong") }
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

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
        val DIRECTIONS_CACHE_TTL: Duration = Duration.ofMinutes(5)
    }
}
