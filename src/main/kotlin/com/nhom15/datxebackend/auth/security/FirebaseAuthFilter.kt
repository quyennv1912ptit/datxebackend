package com.nhom15.datxebackend.auth.security

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.nhom15.datxebackend.auth.service.AuthService
import com.nhom15.datxebackend.common.enums.Role
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class FirebaseAuthFilter(
    private val firebaseAuth: FirebaseAuth,
    private val authService: AuthService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val authorization = request.getHeader(AUTHORIZATION_HEADER)
        if (authorization != null && authorization.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            val idToken = authorization.substring(BEARER_PREFIX.length).trim()
            if (idToken.isNotEmpty()) {
                try {
                    val decodedToken = firebaseAuth.verifyIdToken(idToken, true)
                    val routeRole = expectedRole(request)
                    val headerRole = headerRole(request)
                    val user = if (request.requestURI in DUAL_ROLE_VERIFICATION_ME) {
                        if (headerRole == Role.ADMIN) {
                            authService.findAuthenticatedUser(decodedToken.uid, Role.ADMIN)
                        } else {
                            authService.findAuthenticatedUser(decodedToken.uid, Role.DRIVER)
                                ?: authService.findAuthenticatedUser(decodedToken.uid, Role.ADMIN)
                        }
                    } else {
                        authService.findAuthenticatedUser(decodedToken.uid, routeRole ?: headerRole)
                    }
                    if (user != null && (routeRole == null || user.role == routeRole)) {
                        val authentication = UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")),
                        )
                        SecurityContextHolder.getContext().authentication = authentication
                    } else {
                        SecurityContextHolder.clearContext()
                    }
                } catch (exception: FirebaseAuthException) {
                    log.debug("Rejected invalid or revoked Firebase ID token: {}", exception.message)
                    SecurityContextHolder.clearContext()
                } catch (exception: IllegalArgumentException) {
                    log.debug("Rejected malformed Firebase ID token or X-Role header: {}", exception.message)
                    SecurityContextHolder.clearContext()
                } catch (exception: RuntimeException) {
                    SecurityContextHolder.clearContext()
                    throw exception
                }
            } else {
                SecurityContextHolder.clearContext()
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun expectedRole(request: HttpServletRequest): Role? {
        val uri = request.requestURI
        return when {
            uri.startsWith("/api/admin/") -> Role.ADMIN
            uri.startsWith("/ekyc/driver-verification/v1/admin/") -> Role.ADMIN
            uri in DUAL_ROLE_VERIFICATION_ME -> null
            uri.startsWith("/api/customer/") -> Role.CUSTOMER
            uri.startsWith("/api/driver/") || uri.startsWith("/ekyc/driver-verification/v1/") -> Role.DRIVER
            else -> null
        }
    }

    private fun headerRole(request: HttpServletRequest): Role? {
        val roleHeader = request.getHeader(ROLE_HEADER)?.trim()?.uppercase()
        if (roleHeader.isNullOrEmpty()) return null
        return when (roleHeader) {
            "CUSTOMER", "USER" -> Role.CUSTOMER
            "DRIVER" -> Role.DRIVER
            "ADMIN" -> Role.ADMIN
            else -> throw IllegalArgumentException("Invalid X-Role header")
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(FirebaseAuthFilter::class.java)
        val DUAL_ROLE_VERIFICATION_ME = setOf(
            "/ekyc/driver-verification/v1/me",
            "/api/driver/verification/v1/me",
        )
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val ROLE_HEADER = "X-Role"
    }
}
