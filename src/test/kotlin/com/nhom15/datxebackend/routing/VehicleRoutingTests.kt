package com.nhom15.datxebackend.routing

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.config.properties.MapboxProperties
import com.nhom15.datxebackend.config.properties.PlaceSearchProperties
import com.nhom15.datxebackend.routing.api.DrivingRouteProvider
import com.nhom15.datxebackend.routing.client.MapboxDirectionsClient
import com.nhom15.datxebackend.routing.client.VehicleRouteProvider
import com.nhom15.datxebackend.routing.model.DrivingRoute
import com.nhom15.datxebackend.routing.model.PolylineCodec
import com.nhom15.datxebackend.routing.service.MapService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

class VehicleRoutingTests {
    private val mapper = ObjectMapper()
    private val pickup = GeoPoint(21.030123456, 105.840567891)
    private val dropoff = GeoPoint(21.041234567, 105.851234567)
    @Test fun `preview passes exact coordinates and vehicle type to provider`() {
        val provider = Mockito.mock(DrivingRouteProvider::class.java)
        Mockito.`when`(provider.findRoute(pickup, dropoff, VehicleType.MOTORCYCLE, true)).thenReturn(DrivingRoute(1234.0, 120.0))
        val maps = MapService(RestClient.create(), MapboxProperties("test"), provider, Mockito.mock(StringRedisTemplate::class.java), mapper)
        maps.getDistanceAndDuration("${pickup.lng},${pickup.lat}", "${dropoff.lng},${dropoff.lat}", VehicleType.MOTORCYCLE)
        Mockito.verify(provider).findRoute(pickup, dropoff, VehicleType.MOTORCYCLE, true)
    }
    @Test fun `Goong car uses documented car parameter and exact latitude longitude`() {
        val builder = RestClient.builder().baseUrl("https://rsapi.goong.io")
        val server = MockRestServiceServer.bindTo(builder).build()
        val points = PolylineCodec.encode(listOf(pickup, dropoff), 5).replace("\\", "\\\\")
        server.expect { request ->
            assertEquals("/v2/direction", request.uri.path)
            assertTrue(request.uri.query.contains("vehicle=car"))
            assertTrue(request.uri.query.contains("origin=${pickup.lat},${pickup.lng}"))
        }.andRespond(withSuccess("""{"routes":[{"legs":[{"distance":{"value":1234},"duration":{"value":120}}],"overview_polyline":{"points":"$points"}}]}""", MediaType.APPLICATION_JSON))
        val fallback = Mockito.mock(MapboxDirectionsClient::class.java)
        val route = VehicleRouteProvider(fallback, builder.build(), PlaceSearchProperties(goongApiKey = "test"), mapper).findRoute(pickup, dropoff, VehicleType.CAR, true)
        assertEquals("goong", route.provider); assertEquals(1234.0, route.distanceMeters); assertEquals(2, route.coordinates.size)
        Mockito.verifyNoInteractions(fallback); server.verify()
    }
    @Test fun `motorcycle never guesses Goong bike semantics`() {
        val fallback = Mockito.mock(MapboxDirectionsClient::class.java)
        Mockito.`when`(fallback.findRoute(pickup, dropoff, VehicleType.MOTORCYCLE, true)).thenReturn(DrivingRoute(1000.0, 90.0))
        VehicleRouteProvider(fallback, RestClient.create(), PlaceSearchProperties(goongApiKey = "test"), mapper).findRoute(pickup, dropoff, VehicleType.MOTORCYCLE, true)
        Mockito.verify(fallback).findRoute(pickup, dropoff, VehicleType.MOTORCYCLE, true)
    }
    @Test fun `polyline round trip retains six decimal precision and rejects truncation`() {
        val points = PolylineCodec.decode(PolylineCodec.encode(listOf(pickup, dropoff)), 6)
        assertEquals(pickup.lat, points[0].lat, 0.000001); assertEquals(dropoff.lng, points[1].lng, 0.000001)
        assertThrows(IllegalArgumentException::class.java) { PolylineCodec.decode("_") }
    }
}
