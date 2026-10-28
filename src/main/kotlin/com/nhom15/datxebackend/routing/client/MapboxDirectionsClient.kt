package com.nhom15.datxebackend.routing.client

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.config.properties.MapboxProperties
import com.nhom15.datxebackend.routing.api.DrivingRouteProvider
import com.nhom15.datxebackend.routing.model.DrivingRoute
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

@Service
class MapboxDirectionsClient(
    private val restClient: RestClient,
    private val properties: MapboxProperties,
    private val objectMapper: ObjectMapper,
) : DrivingRouteProvider {

    override fun findRoute(pickup: GeoPoint, dropoff: GeoPoint, vehicleType: VehicleType, includeGeometry: Boolean): DrivingRoute {
        try {
            val profile = if (vehicleType == VehicleType.MOTORCYCLE) "driving" else properties.profile
            val responseBody = restClient.get()
                .uri { builder ->
                    val uri = builder
                        .path("/directions/v5/mapbox/{profile}/{pickup};{dropoff}")
                        .queryParam("alternatives", false)
                        .queryParam("overview", if (includeGeometry) "full" else "false")
                        .queryParam("steps", false)
                        .queryParam("access_token", properties.token.trim())
                    if (includeGeometry) uri.queryParam("geometries", "geojson")
                    uri.build(profile, pickup.asMapboxCoordinate(), dropoff.asMapboxCoordinate())
                }
                .retrieve()
                .body(String::class.java)
                ?: throw routeUnavailable()

            val response = objectMapper.readTree(responseBody)
            val route = response.path("routes").get(0)
            if (response.path("code").asString() != "Ok" || route == null) {
                throw routeUnavailable()
            }
            val distance = route.path("distance").asDouble(Double.NaN)
            val duration = route.path("duration").asDouble(Double.NaN)
            if (!distance.isFinite() || distance < 0.0 ||
                !duration.isFinite() || duration < 0.0
            ) {
                throw routeUnavailable()
            }
            val coordinates = if (includeGeometry) {
                route.path("geometry").path("coordinates").mapNotNull { point ->
                    if (!point.isArray || point.size() < 2) return@mapNotNull null
                    val lng = point[0].asDouble(Double.NaN)
                    val lat = point[1].asDouble(Double.NaN)
                    if (!lng.isFinite() || !lat.isFinite()) null else GeoPoint(lat, lng)
                }
            } else {
                emptyList()
            }

            return DrivingRoute(
                distanceMeters = distance,
                durationSeconds = duration,
                responseBody = responseBody,
                coordinates = coordinates,
                provider = "mapbox",
                limitation = if (vehicleType == VehicleType.MOTORCYCLE) "Mapbox dùng tuyến ô tô gần đúng cho xe máy; chưa bảo đảm đường cấm/hạn chế riêng tại Việt Nam." else null,
            )
        } catch (exception: Exception) {
            // Không gắn exception gốc vì request URL có chứa access token.
            throw routeUnavailable()
        }
    }

    private fun GeoPoint.asMapboxCoordinate(): String = "$lng,$lat"

    private fun routeUnavailable() = AppException(ErrorCode.ROUTE_UNAVAILABLE)

}
