package com.nhom15.datxebackend.config

import com.nhom15.datxebackend.config.properties.MapboxProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Configuration
class MapboxConfig {
    @Bean
    fun mapboxClient(props: MapboxProperties): RestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(props.connectTimeout).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(props.readTimeout)
        }
        return RestClient.builder()
            .baseUrl(props.baseUrl)
            .requestFactory(requestFactory)
            .build()
    }
}
