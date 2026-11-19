package com.nhom15.datxebackend.ekyc

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.ekyc", name = ["enabled"], havingValue = "true")
class EkycConfig {
    @Value("\${app.ekyc.face-comparison-enabled:false}")
    var faceComparisonEnabled: Boolean = false

    @Value("\${app.ekyc.max-retries:1}")
    var maxRetries: Int = 1

    @Value("\${app.ekyc.connect-timeout-ms:5000}")
    var connectTimeoutMs: Long = 5000

    @Value("\${app.ekyc.read-timeout-ms:65000}")
    var readTimeoutMs: Long = 65_000

    @Value("\${app.ekyc.total-deadline-ms:75000}")
    var totalDeadlineMs: Long = 75_000

    @Bean
    fun ekycClient(
        @Value("\${app.ekyc.origin}") origin: String,
        @Value("\${app.ekyc.api-key}") apiKey: String,
        @Value("\${app.ekyc.modal-key}") modalKey: String,
        @Value("\${app.ekyc.modal-secret}") modalSecret: String,
        mapper: ObjectMapper,
    ): EkycClient {
        val base = URI(origin)
        require(
            base.scheme == "https" && base.host?.endsWith(".modal.run") == true &&
                base.userInfo == null && base.port == -1 && base.rawQuery == null &&
                base.rawFragment == null && base.rawPath in setOf("", "/")
        ) { "Configure an HTTPS Modal origin for eKYC" }
        require(apiKey.length >= 32 && modalKey.startsWith("wk-") && modalSecret.startsWith("ws-")) {
            "Configure the dedicated eKYC API key and Modal proxy credentials"
        }
        val effectiveConnectTimeoutMs = connectTimeoutMs.coerceIn(1_000L, 15_000L)
        val effectiveTotalDeadlineMs = totalDeadlineMs.coerceIn(10_000L, 90_000L)
        val effectiveReadTimeoutMs = minOf(readTimeoutMs.coerceIn(5_000L, 90_000L), effectiveTotalDeadlineMs)
        val effectiveAttemptTimeoutMs = minOf(effectiveTotalDeadlineMs, effectiveReadTimeoutMs + effectiveConnectTimeoutMs)
        val http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(effectiveConnectTimeoutMs))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val factory = JdkClientHttpRequestFactory(http)
        // Allow for Modal scale-from-zero and the upstream per-image worker deadline, while keeping timeout bounded.
        factory.setReadTimeout(Duration.ofMillis(effectiveReadTimeoutMs))
        return EkycClient(
            upstream = RestClient.builder().requestFactory(factory).baseUrl(origin.trimEnd('/'))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-API-Key", apiKey)
                .defaultHeader("Modal-Key", modalKey)
                .defaultHeader("Modal-Secret", modalSecret)
                .build(),
            mapper = mapper,
            maxRetries = maxRetries.coerceIn(0, 2),
            faceComparisonEnabled = faceComparisonEnabled,
            totalDeadlineMs = effectiveTotalDeadlineMs,
            attemptTimeoutMs = effectiveAttemptTimeoutMs,
        )
    }
}
