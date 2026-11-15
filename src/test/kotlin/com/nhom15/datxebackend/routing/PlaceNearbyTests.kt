package com.nhom15.datxebackend.routing

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.config.properties.GoongProperties
import com.nhom15.datxebackend.routing.service.PlaceNearbyService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

class PlaceNearbyTests {
    private val mapper = ObjectMapper()

    private fun provider(body: String): Pair<PlaceNearbyService, MockRestServiceServer> {
        val builder = RestClient.builder().baseUrl("https://rsapi.goong.io")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect { request ->
            assertEquals(HttpMethod.POST, request.getMethod())
            assertEquals("/v2/place/nearbysearch", request.uri.path)
            val contentType = request.headers.contentType?.toString() ?: ""
            assertTrue(contentType.contains(MediaType.APPLICATION_JSON_VALUE))
            val payload = mapper.readTree((request as org.springframework.mock.http.client.MockClientHttpRequest).bodyAsString)
            assertEquals(20, payload.path("maxResultCount").asInt())
            assertEquals("DISTANCE", payload.path("rankPreference").asString())
        }.andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
        return PlaceNearbyService(builder.build(), GoongProperties(apiKey = "test-server-key"), mapper) to server
    }

    @Test
    fun `nearby sorts by measured distance and keeps only places inside the radius`() {
        val (service, server) = provider(
            """
            {"results":[
              {"place_id":"far","name":"Quán xa","formatted_address":"Quán xa, Hà Nội","geometry":{"location":{"lat":21.0400,"lng":105.8500}}},
              {"place_id":"near","name":"Quán gần","formatted_address":"Quán gần, Hà Nội","geometry":{"location":{"lat":21.0301,"lng":105.8401}}}
            ]}
            """.trimIndent(),
        )
        val result = service.nearby(GeoPoint(21.0300, 105.8400), radiusMeters = 100, limit = 8)
        assertTrue(result.withinRadius)
        assertEquals(1, result.places.size)
        assertEquals("Quán gần", result.places.single().name)
        assertTrue(result.places.single().distanceMeters <= 100)
        assertEquals("goong", result.places.single().provider)
        server.verify()
    }

    @Test
    fun `nearby falls back to the closest places when the provider has nothing inside the radius`() {
        val (service, server) = provider(
            """
            {"results":[
              {"place_id":"a","name":"Điểm A","formatted_address":"Điểm A, Hà Nội","geometry":{"location":{"lat":21.0500,"lng":105.8500}}},
              {"place_id":"b","name":"Điểm B","formatted_address":"Điểm B, Hà Nội","geometry":{"location":{"lat":21.0600,"lng":105.8600}}}
            ]}
            """.trimIndent(),
        )
        val result = service.nearby(GeoPoint(21.0300, 105.8400), radiusMeters = 100, limit = 8)
        assertFalse(result.withinRadius)
        assertEquals(listOf("Điểm A", "Điểm B"), result.places.map { it.name })
        server.verify()
    }

    @Test
    fun `nearby uses the address head as a name for numeric provider names`() {
        val (service, server) = provider(
            """
            {"results":[{"place_id":"x","name":"4","formatted_address":"4, Ngõ 2 Cầu Bươu, Hà Nội","geometry":{"location":{"lat":21.0301,"lng":105.8401}}}]}
            """.trimIndent(),
        )
        val result = service.nearby(GeoPoint(21.0300, 105.8400), radiusMeters = 100, limit = 8)
        assertEquals("4, Ngõ 2 Cầu Bươu", result.places.single().name)
        assertEquals("4, Ngõ 2 Cầu Bươu, Hà Nội", result.places.single().address)
        server.verify()
    }

    @Test
    fun `nearby without an API key never calls the provider`() {
        val builder = RestClient.builder().baseUrl("https://rsapi.goong.io")
        val server = MockRestServiceServer.bindTo(builder).build()
        val service = PlaceNearbyService(builder.build(), GoongProperties(), mapper)
        val result = service.nearby(GeoPoint(21.03, 105.84), radiusMeters = 100, limit = 8)
        assertTrue(result.places.isEmpty())
        server.verify()
    }
}
