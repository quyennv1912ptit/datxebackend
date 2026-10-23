package com.nhom15.datxebackend.auth.security

import com.google.firebase.auth.FirebaseAuth
import com.nhom15.datxebackend.auth.service.AuthService
import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.config.properties.CorsProperties
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class FirebaseSecurityConfig(
    private val firebaseAuth: FirebaseAuth,
    private val authService: AuthService,
    private val corsProperties: CorsProperties,
    private val objectMapper: ObjectMapper,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val firebaseAuthFilter = FirebaseAuthFilter(firebaseAuth, authService)
        http
            .csrf { it.disable() }
            .cors { }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    // Login and registration carry a Firebase ID token in the request body;
                    // AuthService verifies that token before returning or creating an account.
                    .requestMatchers(
                        "/api/auth/customer/login",
                        "/api/auth/customer/register",
                        "/api/auth/driver/login",
                        "/api/auth/driver/register",
                        "/api/auth/admin/login",
                    ).permitAll()
                    .requestMatchers("/ws-location", "/ws-location/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/api/chat/**").hasAnyRole("CUSTOMER", "DRIVER")
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/location/nearby-drivers").hasRole("ADMIN")
                    .requestMatchers("/api/location/geocode", "/api/location/directions")
                    .hasAnyRole("CUSTOMER", "DRIVER")
                    .requestMatchers("/api/driver/**").hasRole("DRIVER")
                    .requestMatchers("/api/customer/**").hasRole("CUSTOMER")
                    .requestMatchers("/api/notifications", "/api/notifications/**", "/api/device-tokens", "/api/device-tokens/**", "/api/uploads", "/api/uploads/**")
                    .hasAnyRole("CUSTOMER", "DRIVER")
                    .anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, res, _ ->
                    writeError(res, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Chưa đăng nhập hoặc token không hợp lệ")
                }
                it.accessDeniedHandler { _, res, _ ->
                    writeError(res, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN, "Không có quyền truy cập")
                }
            }
            // Chạy bộ lọc firebaseAuthFilter trước khi chạy bộ lọc UsernamePasswordAuthenticationFilter của spring boot
            .addFilterBefore(firebaseAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOriginPatterns = corsProperties.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept", "Idempotency-Key")
            maxAge = 3600
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    private fun writeError(res: HttpServletResponse, status: Int, errorCode: ErrorCode, message: String) {
        res.status = status
        res.contentType = MediaType.APPLICATION_JSON_VALUE
        res.characterEncoding = "UTF-8"
        res.writer.write(objectMapper.writeValueAsString(ApiResponse.fail(errorCode, message)))
    }
}
