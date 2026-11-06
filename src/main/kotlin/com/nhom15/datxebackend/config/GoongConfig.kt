package com.nhom15.datxebackend.config

import com.nhom15.datxebackend.config.properties.GoongProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Configuration
class GoongConfig {
    @Bean
    fun goongClient(properties: GoongProperties): RestClient {
        val factory = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build())
        factory.setReadTimeout(properties.readTimeout)
        return RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(factory)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.USER_AGENT, "PtitGo/1.0")
            .build()
    }
}
