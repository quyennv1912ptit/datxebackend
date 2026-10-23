package com.nhom15.datxebackend.auth.security

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.nhom15.datxebackend.auth.service.AuthService
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
                    val user = authService.findAuthenticatedUser(decodedToken.uid)
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
                    log.debug("Rejected malformed Firebase ID token: {}", exception.message)
                    SecurityContextHolder.clearContext()
                }
            } else {
                SecurityContextHolder.clearContext()
            }
        }

        filterChain.doFilter(request, response)
    }

    private companion object {
        val log = LoggerFactory.getLogger(FirebaseAuthFilter::class.java)
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
