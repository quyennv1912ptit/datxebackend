package com.nhom15.datxebackend.routing.client

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.config.properties.GoongProperties
import com.nhom15.datxebackend.routing.api.DrivingRouteProvider
import com.nhom15.datxebackend.routing.model.DrivingRoute
import com.nhom15.datxebackend.routing.model.PolylineCodec
import com.nhom15.datxebackend.routing.model.RouteStep
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

@Service
class GoongDirectionsClient(
    private val goong: RestClient,
    private val properties: GoongProperties,
    private val mapper: ObjectMapper,
) : DrivingRouteProvider {
    override fun findRoute(pickup: GeoPoint, dropoff: GeoPoint, vehicleType: VehicleType, includeGeometry: Boolean): DrivingRoute {
        val motorcycle = vehicleType == VehicleType.MOTORCYCLE
        val path = if (motorcycle) "/Direction" else "/v2/direction"
        val vehicle = if (motorcycle) "bike" else "car"
        try {
            require(properties.apiKey.isNotBlank())
            val body = goong.get().uri { builder -> builder.path(path)
                .queryParam("origin", "${pickup.lat},${pickup.lng}")
                .queryParam("destination", "${dropoff.lat},${dropoff.lng}")
                .queryParam("vehicle", vehicle)
                .queryParam("alternatives", false)
                .queryParam("api_key", properties.apiKey.trim()).build()
            }.retrieve().body(String::class.java) ?: throw AppException(ErrorCode.ROUTE_UNAVAILABLE)
            val root = mapper.readTree(body)
            val route = root.path("routes").firstOrNull() ?: throw AppException(ErrorCode.ROUTE_UNAVAILABLE)
            val legs = route.path("legs").toList()
            require(legs.isNotEmpty())
            val distance = legs.sumOf { it.path("distance").path("value").asDouble(Double.NaN) }
            val duration = legs.sumOf { it.path("duration").path("value").asDouble(Double.NaN) }
            require(distance.isFinite() && distance >= 0 && duration.isFinite() && duration >= 0)
            val points = if (includeGeometry) {
                PolylineCodec.decode(route.path("overview_polyline").path("points").asString(""))
                    .also { require(it.size >= 2) }
            } else emptyList()
            val steps = if (includeGeometry) legs.flatMap { leg ->
                leg.path("steps").mapNotNull { step ->
                    val instruction = HTML_TAG.replace(step.path("html_instructions").asString(""), "").trim()
                    val meters = step.path("distance").path("value").asDouble(Double.NaN)
                    if (instruction.isBlank() || !meters.isFinite() || meters < 0) null
                    else RouteStep(instruction, meters)
                }
            } else emptyList()
            return DrivingRoute(distance, duration, body, points, provider = "goong", steps = steps)
        } catch (_: Exception) {
            // Never expose provider request URLs or API keys to callers.
            throw AppException(ErrorCode.ROUTE_UNAVAILABLE)
        }
    }

    private companion object {
        val HTML_TAG = Regex("<[^>]*>")
    }
}
