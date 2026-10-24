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
                    val decodedToken = firebaseAuth.verifyIdToken(idToken)
                    val roleHint = requestedRole(request)
                    val user = authService.findAuthenticatedUser(decodedToken.uid, roleHint)
                    if (user != null) {
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
                    log.debug("Rejected invalid Firebase ID token: {}", exception.message)
                    SecurityContextHolder.clearContext()
                } catch (exception: IllegalArgumentException) {
                    log.debug("Rejected malformed Firebase ID token or X-Role header: {}", exception.message)
                    SecurityContextHolder.clearContext()
                }
            } else {
                SecurityContextHolder.clearContext()
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun requestedRole(request: HttpServletRequest): Role? {
        val roleHeader = request.getHeader(ROLE_HEADER)?.trim()?.uppercase()
        if (!roleHeader.isNullOrEmpty()) {
            return when (roleHeader) {
                "CUSTOMER", "USER" -> Role.CUSTOMER
                "DRIVER" -> Role.DRIVER
                "ADMIN" -> Role.ADMIN
                else -> throw IllegalArgumentException("Invalid X-Role header")
            }
        }

        // Role-scoped routes remain compatible with clients that predate the role header.
        return when {
            request.requestURI.startsWith("/api/customer/") -> Role.CUSTOMER
            request.requestURI.startsWith("/api/driver/") -> Role.DRIVER
            request.requestURI.startsWith("/api/admin/") -> Role.ADMIN
            else -> null
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(FirebaseAuthFilter::class.java)
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val ROLE_HEADER = "X-Role"
    }
}
