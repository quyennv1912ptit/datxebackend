package com.nhom15.datxebackend.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "places")
data class PlaceSearchProperties(
    val provider: String = "auto",
    val goongApiKey: String = "",
    val goongBaseUrl: String = "https://rsapi.goong.io",
) {
    init {
        require(provider in setOf("auto", "mapbox", "goong")) { "places.provider phải là auto, mapbox hoặc goong" }
        require(goongBaseUrl.startsWith("https://") || goongBaseUrl.startsWith("http://"))
    }
    val useGoong: Boolean get() = provider != "mapbox" && goongApiKey.isNotBlank()
}
