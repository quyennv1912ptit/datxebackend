package com.nhom15.datxebackend.security

import com.google.firebase.auth.FirebaseAuth
import com.nhom15.datxebackend.auth.security.FirebaseSecurityConfig
import com.nhom15.datxebackend.auth.service.AuthService
import com.nhom15.datxebackend.config.properties.CorsProperties
import java.util.Properties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.ObjectMapper

class FrontendOriginConfigTests {
    @Test
    fun `production profiles require explicit frontend origins and a configured trusted ingress`() {
        for (profile in listOf("prod", "production")) {
            val config = Properties()
            javaClass.getResourceAsStream("/application-$profile.properties")!!.use(config::load)
            assertEquals("\${APP_CORS_ALLOWED_ORIGINS:}", config.getProperty("app.cors.allowed-origins"))
            assertEquals("native", config.getProperty("server.forward-headers-strategy"))
            assertEquals("CF-Connecting-IP", config.getProperty("server.tomcat.remoteip.remote-ip-header"))
            assertEquals("\${APP_TRUSTED_EDGE_PROXIES:(?!)}", config.getProperty("server.tomcat.remoteip.internal-proxies"))
        }
    }

    @Test
    fun `approved frontend works on HTTP and cookie-free WebSocket handshake`() {
        val origin = "https://frontend.example.test"
        val config = FirebaseSecurityConfig(
            mock(FirebaseAuth::class.java),
            mock(AuthService::class.java),
            CorsProperties(listOf(origin)),
            ObjectMapper(),
        ).corsConfigurationSource()

        for (path in listOf("/api/customer/rides", "/ws-location")) {
            val request = MockHttpServletRequest("GET", path)
            val cors = config.getCorsConfiguration(request)!!
            assertEquals(origin, cors.checkOrigin(origin))
            assertEquals(listOf("https://go-admin-mu.vercel.app", origin), cors.allowedOrigins)
        }

        val handshake = config.getCorsConfiguration(MockHttpServletRequest("GET", "/ws-location"))!!
        assertFalse(handshake.allowCredentials!!)
    }
}
