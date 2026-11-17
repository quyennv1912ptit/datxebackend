package com.nhom15.datxebackend.driver.verification

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

@Component
@ConditionalOnProperty(prefix = "app.verification-sync", name = ["enabled"], havingValue = "true")
class VerificationSync(
    private val projection: VerificationProjectionRepository,
    @Value("\${app.verification-sync.origin}") origin: String,
    @Value("\${app.verification-sync.key}") key: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val upstream: RestClient

    init {
        val uri = URI(origin)
        val loopback = uri.host in setOf("127.0.0.1", "localhost", "[::1]")
        require((uri.scheme == "https" || (uri.scheme == "http" && loopback)) &&
            uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath in setOf("", "/"))
        require(key.length >= 32)
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build()
        val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(Duration.ofSeconds(3)) }
        upstream = RestClient.builder().baseUrl(origin.trimEnd('/')).requestFactory(factory)
            .defaultHeader("X-Verification-Sync-Key", key).build()
    }

    @Scheduled(fixedDelay = 2000)
    fun reconcile() {
        try {
            val cursor = projection.cursor()
            val response = upstream.get().uri("/ekyc/driver-verification/v1/internal/decision-events?after={cursor}", cursor)
                .retrieve().body(VerificationFeedEnvelope::class.java)
            check(response?.success == true)
            projection.applyPage(cursor, checkNotNull(response.data))
        } catch (error: Exception) {
            log.warn("verification_sync_failed type={}", error.javaClass.simpleName)
            runCatching { projection.failed() }
        }
    }

    fun confirms(local: VerificationHead): Boolean = runCatching {
        val response = upstream.get().uri("/ekyc/driver-verification/v1/internal/decision-head")
            .retrieve().body(VerificationHeadEnvelope::class.java)
        response?.success == true && response.data == local
    }.getOrDefault(false)
}
