package com.nhom15.datxebackend.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "goong")
data class GoongProperties(
    val apiKey: String = "",
    val baseUrl: String = "https://rsapi.goong.io",
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(5),
) {
    init {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))
        require(!connectTimeout.isZero && !connectTimeout.isNegative)
        require(!readTimeout.isZero && !readTimeout.isNegative)
    }
}
