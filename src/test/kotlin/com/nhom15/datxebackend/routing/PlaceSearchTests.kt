package com.nhom15.datxebackend.routing

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.config.GoongConfig
import com.nhom15.datxebackend.config.properties.GoongProperties
import com.nhom15.datxebackend.routing.service.PlaceSearchService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class PlaceSearchTests {
    private val mapper = ObjectMapper()
    private fun params(uri: java.net.URI): Map<String, String> = uri.rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
    }
    @Test
    fun `missing key fails locally without a provider request`() {
        val builder = RestClient.builder().baseUrl("https://rsapi.goong.io")
        val server = MockRestServiceServer.bindTo(builder).build()
        val service = PlaceSearchService(builder.build(), GoongProperties(), mapper)
        assertEquals(ErrorCode.LOCATION_SEARCH_UNAVAILABLE,
            assertThrows(AppException::class.java) { service.search("Hồ Tây", null) }.errorCode)
        server.verify()
    }

    @Test
    fun `Goong zero results returns an empty list`() {
        val builder = RestClient.builder().baseUrl("https://rsapi.goong.io")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect { request -> assertEquals("/v2/place/autocomplete", request.uri.path) }
            .andRespond(withSuccess("""{"status":"ZERO_RESULTS","predictions":[]}""", MediaType.APPLICATION_JSON))
        val service = PlaceSearchService(builder.build(), GoongProperties(apiKey = "test"), mapper)
        assertTrue(service.search("Hồ Tây", null).places.isEmpty())
        server.verify()
    }

    @Test
    fun `Goong autocomplete sends Vietnamese query and latitude first with no hard radius to one city`() {
        val builder = RestClient.builder().baseUrl("https://rsapi.goong.io")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect { request ->
            assertEquals("/v2/place/autocomplete", request.uri.path)
            val query = params(request.uri)
            assertEquals("Ngách 12/3 Hồ Tây", query["input"])
            assertEquals("21.03,105.84", query["location"])
            assertEquals("2000", query["radius"])
            assertEquals("10", query["limit"])
            assertEquals("true", query["more_compound"])
        }.andRespond(withSuccess("""{"status":"OK","predictions":[{"place_id":"id/a+b==","description":"Ngách 12/3, Hà Nội","structured_formatting":{"main_text":"Ngách 12/3"},"deprecated_description":"Ngách 12/3, quận cũ, Hà Nội"}]}""", MediaType.APPLICATION_JSON))
        val service = PlaceSearchService(builder.build(), GoongProperties(apiKey = "test-server-key"), mapper)
        val response = service.search("Ngách 12/3 Hồ Tây", GeoPoint(21.03, 105.84))
        assertEquals("goong", response.provider)
        assertFalse(response.limitedCoverage)
        assertNull(response.places.single().coordinate)
        assertEquals("Ngách 12/3, Hà Nội", response.places.single().address)
        assertNotNull(response.places.single().secondaryAddress)
        assertFalse(mapper.writeValueAsString(response).contains("test-server-key"))
        server.verify()
    }

    @Test
    fun `Goong detail uses encoded place ID and returns verified coordinates`() {
        val builder = RestClient.builder().baseUrl("https://rsapi.goong.io")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect { request ->
            assertEquals("/v2/place/detail", request.uri.path)
            assertEquals("id/a+b==", params(request.uri)["place_id"])
        }.andRespond(withSuccess("""{"status":"OK","result":{"name":"Ngách 12/3","formatted_address":"Ngách 12/3, Hà Nội","geometry":{"location":{"lat":21.03,"lng":105.84}}}}""", MediaType.APPLICATION_JSON))
        val place = PlaceSearchService(builder.build(), GoongProperties(apiKey = "test"), mapper).detail("id/a+b==")
        assertEquals(GeoPoint(21.03, 105.84), place.coordinate)
        server.verify()
    }

    @Test
    fun `reverse Goong geocoding preserves exact pin instead of provider nearby center`() {
        val builder = RestClient.builder().baseUrl("https://rsapi.goong.io")
        val server = MockRestServiceServer.bindTo(builder).build()
        val point = GeoPoint(21.0301234, 105.8405678)
        server.expect { request ->
            assertEquals("/v2/geocode", request.uri.path)
            assertEquals("${point.lat},${point.lng}", params(request.uri)["latlng"])
        }.andRespond(withSuccess("""{"status":"OK","results":[{"formatted_address":"Ngõ 12, Hà Nội","geometry":{"location":{"lat":21.04,"lng":105.85}}}]}""", MediaType.APPLICATION_JSON))
        val place = PlaceSearchService(builder.build(), GoongProperties(apiKey = "test"), mapper).reverse(point)
        assertEquals(point, place.coordinate)
        server.verify()
    }

    @Test
    fun `legacy geocode preserves feature coordinates while using Goong`() {
        val builder = RestClient.builder().baseUrl("https://rsapi.goong.io")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect { request ->
            assertEquals("/v2/geocode", request.uri.path)
            assertEquals("Hồ Tây", params(request.uri)["address"])
        }.andRespond(withSuccess("""{"status":"OK","results":[{"formatted_address":"Hồ Tây, Hà Nội","geometry":{"location":{"lat":21.04,"lng":105.82}}}]}""", MediaType.APPLICATION_JSON))
        val service = PlaceSearchService(builder.build(), GoongProperties(apiKey = "test"), mapper)
        val feature = mapper.readTree(service.geocode("Hồ Tây", null)).path("features").get(0)
        assertEquals("Hồ Tây, Hà Nội", feature.path("place_name").asString())
        assertEquals(105.82, feature.path("geometry").path("coordinates").get(0).asDouble())
        server.verify()
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_GOONG_LIVE_TEST", matches = "true")
    fun `live Goong returns nearby places and reverse geocoding`() {
        val key = System.getenv("GOONG_API_KEY") ?: error("Missing Goong key")
        val properties = GoongProperties(apiKey = key)
        val service = PlaceSearchService(GoongConfig().goongClient(properties), properties, mapper)
        val proximity = GeoPoint(21.0285, 105.8542)
        try {
            val place = service.search("Hồ Tây", proximity).places.first { it.address.contains("Hồ Tây") }
            assertNotNull(service.detail(place.id).coordinate)
            val reverse = service.reverse(proximity)
            assertEquals(proximity, reverse.coordinate)
            assertTrue(reverse.address.isNotBlank())
        } catch (_: com.nhom15.datxebackend.common.exception.AppException) {
            // Do not include an upstream exception/URL containing the real access token.
            fail<Unit>("Live provider unavailable")
        }
    }
}
