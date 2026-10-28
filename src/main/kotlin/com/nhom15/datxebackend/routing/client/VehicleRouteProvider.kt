package com.nhom15.datxebackend.routing.client

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.config.properties.PlaceSearchProperties
import com.nhom15.datxebackend.routing.api.DrivingRouteProvider
import com.nhom15.datxebackend.routing.model.DrivingRoute
import com.nhom15.datxebackend.routing.model.PolylineCodec
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

@Primary
@Service
class VehicleRouteProvider(
    private val mapbox: MapboxDirectionsClient,
    @param:Qualifier("goongPlacesClient") private val goong: RestClient,
    private val properties: PlaceSearchProperties,
    private val mapper: ObjectMapper,
) : DrivingRouteProvider {
    override fun findRoute(pickup: GeoPoint, dropoff: GeoPoint, vehicleType: VehicleType, includeGeometry: Boolean): DrivingRoute {
        // Goong's Direction docs currently disagree about bike/motorcycle semantics.
        // Never silently use a bicycle profile for MOTORCYCLE.
        if (!properties.useGoong || vehicleType == VehicleType.MOTORCYCLE) return mapbox.findRoute(pickup, dropoff, vehicleType, includeGeometry)
        try {
            val body = goong.get().uri { builder -> builder.path("/v2/direction")
                .queryParam("origin", "${pickup.lat},${pickup.lng}")
                .queryParam("destination", "${dropoff.lat},${dropoff.lng}")
                .queryParam("vehicle", "car").queryParam("alternatives", false)
                .queryParam("api_key", properties.goongApiKey.trim()).build()
            }.retrieve().body(String::class.java) ?: throw AppException(ErrorCode.ROUTE_UNAVAILABLE)
            val root = mapper.readTree(body)
            val route = root.path("routes").firstOrNull() ?: throw AppException(ErrorCode.ROUTE_UNAVAILABLE)
            val legs = route.path("legs").toList()
            require(legs.isNotEmpty())
            val distance = legs.sumOf { it.path("distance").path("value").asDouble(Double.NaN) }
            val duration = legs.sumOf { it.path("duration").path("value").asDouble(Double.NaN) }
            require(distance.isFinite() && distance >= 0 && duration.isFinite() && duration >= 0)
            val points = PolylineCodec.decode(route.path("overview_polyline").path("points").asString(""))
            require(!includeGeometry || points.size >= 2)
            return DrivingRoute(distance, duration, coordinates = if (includeGeometry) points else emptyList(), provider = "goong")
        } catch (_: Exception) {
            // Same vehicle semantics are retained on upstream failure; no provider URLs/keys escape.
            return mapbox.findRoute(pickup, dropoff, vehicleType, includeGeometry)
        }
    }
}
