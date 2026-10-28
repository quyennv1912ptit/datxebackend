package com.nhom15.datxebackend.routing

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.config.properties.MapboxProperties
import com.nhom15.datxebackend.config.properties.PlaceSearchProperties
import com.nhom15.datxebackend.routing.api.DrivingRouteProvider
import com.nhom15.datxebackend.routing.service.MapService
import com.nhom15.datxebackend.routing.service.PlaceSearchParsing
import com.nhom15.datxebackend.routing.service.PlaceSearchService
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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class PlaceSearchTests {
    private val mapper = ObjectMapper()
    private val maps = Mockito.mock(MapService::class.java)
    private fun params(uri: java.net.URI): Map<String, String> = uri.rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
    }
    private fun feature(name: String, relevance: Double = 1.0, number: String = "") = """
        {"id":"$name","text":"$name","address":"$number","place_name":"$number $name, Hà Nội, Việt Nam","relevance":$relevance,"geometry":{"coordinates":[105.8,21.03]}}
    """.trimIndent()

    @Test
    fun `missing key falls back to Mapbox even when goong is requested`() {
        for (provider in listOf("auto", "goong")) {
            val properties = PlaceSearchProperties(provider = provider, goongApiKey = " ")
            assertFalse(properties.useGoong)
            Mockito.`when`(maps.getCoordinatesFromAddress("Hồ Tây", null)).thenReturn("""{"features":[${feature("Phố Tây Sơn", 0.5)},${feature("Hồ Tây")}]}""")
            val service = PlaceSearchService(maps, RestClient.create(), properties, mapper)
            val response = service.search("Hồ Tây", null)
            assertEquals("mapbox", response.provider)
            assertTrue(response.limitedCoverage)
            assertEquals(listOf("Hồ Tây"), response.places.map { it.name })
        }
    }

    @Test
    fun `absent landmark returns empty instead of unrelated streets`() {
        Mockito.`when`(maps.getCoordinatesFromAddress("Hồ Tây", null)).thenReturn("""{"features":[${feature("Phố Tây Sơn", 0.5)},${feature("Tây Đằng", 0.5)}]}""")
        val response = PlaceSearchService(maps, RestClient.create(), PlaceSearchProperties(), mapper).search("Hồ Tây", null)
        assertTrue(response.places.isEmpty())
    }

    @Test
    fun `all Vietnamese words and house numbers must match with accent insensitive autocomplete`() {
        assertTrue(PlaceSearchParsing.matches("ngo 165 cau gi", "Ngõ 165 - Cầu Giấy, Hà Nội"))
        assertFalse(PlaceSearchParsing.matches("Ngõ 165 Cầu Giấy", "Ngõ 1651 Cầu Giấy, Hà Nội"))
        assertFalse(PlaceSearchParsing.matches("Hồ Tây", "Phố Tây Sơn, Hà Nội"))
        assertFalse(PlaceSearchParsing.matches("Ngách 12/3", "Ngõ 12/30, Hà Nội"))
        assertTrue(PlaceSearchParsing.matches("ngach 12/3", "Ngách 12/3, Đường Cầu Giấy"))
        val place = PlaceSearchParsing.mapbox(mapper.readTree(feature("Ngõ 165 Cầu Giấy", number = "12/3")))!!
        assertEquals("12/3 Ngõ 165 Cầu Giấy", place.name)
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
        val service = PlaceSearchService(maps, builder.build(), PlaceSearchProperties(goongApiKey = "test-server-key"), mapper)
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
        val place = PlaceSearchService(maps, builder.build(), PlaceSearchProperties(goongApiKey = "test"), mapper).detail("id/a+b==")
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
        val place = PlaceSearchService(maps, builder.build(), PlaceSearchProperties(goongApiKey = "test"), mapper).reverse(point)
        assertEquals(point, place.coordinate)
        server.verify()
    }

    @Test
    fun `legacy Mapbox request uses strict matching ten results and exact UTF8 input`() {
        val builder = RestClient.builder().baseUrl("https://api.mapbox.com")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect { request ->
            assertEquals("/geocoding/v5/mapbox.places/Hồ Tây.json", request.uri.path)
            val query = params(request.uri)
            assertEquals("false", query["fuzzyMatch"])
            assertEquals("10", query["limit"])
            assertEquals("105.84,21.03", query["proximity"])
        }.andRespond(withSuccess("""{"features":[]}""", MediaType.APPLICATION_JSON))
        val service = MapService(builder.build(), MapboxProperties(token = "test"), Mockito.mock(DrivingRouteProvider::class.java), Mockito.mock(StringRedisTemplate::class.java), mapper)
        service.getCoordinatesFromAddress("Hồ Tây", GeoPoint(21.03, 105.84))
        server.verify()
    }

    @Test
    fun `Mapbox reverse omits forward-only options and keeps original pin`() {
        val builder = RestClient.builder().baseUrl("https://api.mapbox.com")
        val server = MockRestServiceServer.bindTo(builder).build()
        val point = GeoPoint(21.0301234, 105.8405678)
        server.expect { request ->
            assertEquals("/geocoding/v5/mapbox.places/${point.lng},${point.lat}.json", request.uri.path)
            val query = params(request.uri)
            assertFalse(query.containsKey("limit"))
            assertFalse(query.containsKey("autocomplete"))
            assertFalse(query.containsKey("fuzzyMatch"))
        }.andRespond(withSuccess("""{"features":[${feature("Ngõ 12") }]}""", MediaType.APPLICATION_JSON))
        val maps = MapService(builder.build(), MapboxProperties(token = "test"), Mockito.mock(DrivingRouteProvider::class.java), Mockito.mock(StringRedisTemplate::class.java), mapper)
        val place = PlaceSearchService(maps, RestClient.create(), PlaceSearchProperties(), mapper).reverse(point)
        assertEquals(point, place.coordinate)
        server.verify()
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_MAPBOX_LIVE_TEST", matches = "true")
    fun `live Mapbox regressions preserve alley address and never substitute Tay Son for Ho Tay`() {
        val key = System.getenv("MAPBOX_LIVE_TEST_TOKEN") ?: error("Missing test token")
        val maps = MapService(RestClient.builder().baseUrl("https://api.mapbox.com").build(), MapboxProperties(token = key), Mockito.mock(DrivingRouteProvider::class.java), Mockito.mock(StringRedisTemplate::class.java), mapper)
        val service = PlaceSearchService(maps, RestClient.create(), PlaceSearchProperties(), mapper)
        val proximity = GeoPoint(21.0285, 105.8542)
        try {
            assertTrue(service.search("Hồ Tây", proximity).places.none { it.name.contains("Tây Sơn") })
            assertTrue(service.search("Ngõ 165 Cầu Giấy Hà Nội", proximity).places.any { it.address.contains("165") && it.address.contains("Cầu Giấy") })
            val reverse = service.reverse(proximity)
            assertEquals(proximity, reverse.coordinate)
            assertTrue(reverse.address.isNotBlank())
        } catch (_: com.nhom15.datxebackend.common.exception.AppException) {
            // Do not include an upstream exception/URL containing the real access token.
            fail<Unit>("Live provider unavailable")
        }
    }
}
