package com.nhom15.datxebackend.routing

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.config.GoongConfig
import com.nhom15.datxebackend.config.properties.GoongProperties
import com.nhom15.datxebackend.routing.api.DrivingRouteProvider
import com.nhom15.datxebackend.routing.client.GoongDirectionsClient
import com.nhom15.datxebackend.routing.model.DrivingRoute
import com.nhom15.datxebackend.routing.model.PolylineCodec
import com.nhom15.datxebackend.routing.service.MapService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
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
        val maps = MapService(provider, Mockito.mock(StringRedisTemplate::class.java), mapper)
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
        val route = GoongDirectionsClient(builder.build(), GoongProperties(apiKey = "test"), mapper).findRoute(pickup, dropoff, VehicleType.CAR, true)
        assertEquals("goong", route.provider); assertEquals(1234.0, route.distanceMeters); assertEquals(2, route.coordinates.size)
        server.verify()
    }
    @Test fun `motorcycle uses the Goong bike profile and returns normalized directions steps`() {
        val builder = RestClient.builder().baseUrl("https://rsapi.goong.io")
        val server = MockRestServiceServer.bindTo(builder).build()
        val points = mapper.writeValueAsString(PolylineCodec.encode(listOf(pickup, dropoff), 5))
        server.expect { request ->
            assertEquals("/Direction", request.uri.path)
            assertTrue(request.uri.query.contains("vehicle=bike"))
        }.andRespond(withSuccess("""{"routes":[{"legs":[{"distance":{"value":1234},"duration":{"value":120},"steps":[{"distance":{"value":85},"html_instructions":"Rẽ phải vào <b>Trần Phú</b>"}]}],"overview_polyline":{"points":$points}}]}""", MediaType.APPLICATION_JSON))
        val route = GoongDirectionsClient(builder.build(), GoongProperties(apiKey = "test"), mapper)
            .findRoute(pickup, dropoff, VehicleType.MOTORCYCLE, true)
        assertEquals("goong", route.provider)
        assertEquals("Rẽ phải vào Trần Phú", route.steps.single().instruction)
        assertEquals(85.0, route.steps.single().distanceMeters)
        server.verify()
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GOONG_LIVE_TEST", matches = "true")
    fun `live Goong returns geometry and duration for both vehicle types`() {
        val properties = GoongProperties(apiKey = System.getenv("GOONG_API_KEY") ?: error("Missing Goong key"))
        val client = GoongDirectionsClient(GoongConfig().goongClient(properties), properties, mapper)
        for (vehicle in listOf(VehicleType.CAR, VehicleType.MOTORCYCLE)) {
            val route = client.findRoute(pickup, dropoff, vehicle, true)
            assertTrue(route.distanceMeters > 0)
            assertTrue(route.durationSeconds > 0)
            assertTrue(route.coordinates.size > 1)
        }
    }
    @Test fun `polyline round trip retains six decimal precision and rejects truncation`() {
        val points = PolylineCodec.decode(PolylineCodec.encode(listOf(pickup, dropoff)), 6)
        assertEquals(pickup.lat, points[0].lat, 0.000001); assertEquals(dropoff.lng, points[1].lng, 0.000001)
        assertThrows(IllegalArgumentException::class.java) { PolylineCodec.decode("_") }
    }
}
