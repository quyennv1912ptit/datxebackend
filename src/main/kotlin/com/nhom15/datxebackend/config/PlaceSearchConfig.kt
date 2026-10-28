package com.nhom15.datxebackend.config

import com.nhom15.datxebackend.config.properties.MapboxProperties
import com.nhom15.datxebackend.config.properties.PlaceSearchProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Configuration
class PlaceSearchConfig {
    @Bean("goongPlacesClient")
    fun goongPlacesClient(places: PlaceSearchProperties, timeouts: MapboxProperties): RestClient {
        val factory = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeouts.connectTimeout).build())
        factory.setReadTimeout(timeouts.readTimeout)
        return RestClient.builder().baseUrl(places.goongBaseUrl).requestFactory(factory).build()
    }
}
