package com.nhom15.datxebackend.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "mapbox")
data class MapboxProperties(
    val token: String,
    val baseUrl: String = "https://api.mapbox.com",
    val profile: String = "driving-traffic",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(5)
) {
    init {
        require(token.isNotBlank()) { "mapbox.token không được để trống" }
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
            "mapbox.base-url phải là HTTP(S) URL"
        }
        require(profile.isNotBlank()) { "mapbox.profile không được để trống" }
        require(!connectTimeout.isNegative && !connectTimeout.isZero) { "mapbox.connect-timeout phải lớn hơn 0" }
        require(!readTimeout.isNegative && !readTimeout.isZero) { "mapbox.read-timeout phải lớn hơn 0" }
    }
}
