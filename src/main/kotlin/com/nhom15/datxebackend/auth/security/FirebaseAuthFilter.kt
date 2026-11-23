package com.nhom15.datxebackend.auth.security

import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.nhom15.datxebackend.auth.service.AuthService
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
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
                    // checkRevoked: a password change / admin revocation must invalidate the token here too.
                    val decodedToken = firebaseAuth.verifyIdToken(idToken, true)
                    val routeRole = expectedRole(request)
                    val headerRole = requestedRole(request)

                    // X-Role is only a profile disambiguator for routes shared by several roles.
                    // It never grants a role. ADMIN is accepted on admin routes, on the dual-role
                    // verification endpoints and on shared routes where the security chain already
                    // permits ADMIN; admins commonly send X-Role: ADMIN on every request.
                    val dualRoleVerificationMe = request.requestURI in DUAL_ROLE_VERIFICATION_ME
                    val adminHeaderAllowed = routeRole == Role.ADMIN || dualRoleVerificationMe || isAdminSharedRoute(request.requestURI)
                    val user = if (headerRole == Role.ADMIN && !adminHeaderAllowed) {
                        log.debug("Rejected X-Role ADMIN on non-admin route {}", request.requestURI)
                        null
                    } else if (dualRoleVerificationMe) {
                        // These paths imply DRIVER for driver profiles, but admins are allowed
                        // too. A roleless lookup would reject a driver who also has a customer
                        // profile, so try the two permitted profiles explicitly.
                        if (headerRole == Role.ADMIN) {
                            authService.findAuthenticatedUser(decodedToken.uid, Role.ADMIN)
                        } else {
                            authService.findAuthenticatedUser(decodedToken.uid, Role.DRIVER)
                                ?: authService.findAuthenticatedUser(decodedToken.uid, Role.ADMIN)
                        }
                    } else {
                        authService.findAuthenticatedUser(decodedToken.uid, routeRole ?: headerRole)
                    }
                    if (user == null || (routeRole != null && user.role != routeRole) ||
                        !canAccessDriverRoute(user, request.requestURI)
                    ) {
                        SecurityContextHolder.clearContext()
                    } else {
                        val authentication = UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")),
                        )
                        SecurityContextHolder.getContext().authentication = authentication
                    }
                } catch (exception: FirebaseAuthException) {
                    if (exception.authErrorCode == AuthErrorCode.REVOKED_ID_TOKEN) {
                        log.debug("Rejected revoked Firebase ID token")
                    } else {
                        log.debug("Rejected invalid Firebase ID token: {}", exception.message)
                    }
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

    /**
     * Role implied by the endpoint itself. This is authoritative for role-scoped routes so a
     * client cannot select a different profile with a header. Routes shared by several roles
     * return null; there the header is only used to disambiguate a dual CUSTOMER/DRIVER identity.
     */
    private fun expectedRole(request: HttpServletRequest): Role? {
        val uri = request.requestURI
        return when {
            uri.startsWith(ADMIN_API_PREFIX) -> Role.ADMIN
            uri.startsWith(ADMIN_EKYC_PREFIX) -> Role.ADMIN
            // Endpoint song vai trò: SecurityConfig cho phép DRIVER + ADMIN và kontroler
            // trả "admin" cho ADMIN. Ép DRIVER ở đây sẽ khóa mọi admin khỏi chính endpoint của họ.
            uri in DUAL_ROLE_VERIFICATION_ME -> null
            uri.startsWith(CUSTOMER_API_PREFIX) -> Role.CUSTOMER
            uri.startsWith(DRIVER_API_PREFIX) || uri.startsWith(DRIVER_EKYC_PREFIX) -> Role.DRIVER
            uri.startsWith(CUSTOMER_NEARBY_DRIVERS) -> Role.CUSTOMER
            uri == ADMIN_NEARBY_DRIVERS -> Role.ADMIN
            else -> null
        }
    }

    /** Shared routes that Spring Security also opens to ADMIN (upload signature, profile, support). */
    private fun isAdminSharedRoute(uri: String): Boolean =
        ADMIN_SHARED_ROUTE_PREFIXES.any { uri.startsWith(it) }

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

        // Role-scoped routes remain compatible with clients that predate the role header;
        // expectedRole() already derives the same role from the URI.
        return expectedRole(request)
    }

    /** Unverified driver profiles may only use onboarding/verification endpoints. */
    private fun canAccessDriverRoute(user: AuthUser, uri: String): Boolean {
        if (user.role != Role.DRIVER || !uri.startsWith(DRIVER_API_PREFIX)) return true
        if (DRIVER_ONBOARDING_PREFIXES.any { uri.startsWith(it) }) return true
        if (user.verificationStatus == APPROVED_STATUS) return true
        log.debug("Unapproved driver profile refused on privileged route {}", uri)
        return false
    }

    private companion object {
        val log = LoggerFactory.getLogger(FirebaseAuthFilter::class.java)
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val ROLE_HEADER = "X-Role"
        const val APPROVED_STATUS = "APPROVED"
        const val ADMIN_API_PREFIX = "/api/admin/"
        const val CUSTOMER_API_PREFIX = "/api/customer/"
        const val DRIVER_API_PREFIX = "/api/driver/"
        const val ADMIN_EKYC_PREFIX = "/ekyc/driver-verification/v1/admin/"
        val DUAL_ROLE_VERIFICATION_ME = setOf(
            "/ekyc/driver-verification/v1/me",
            "/api/driver/verification/v1/me",
        )
        val ADMIN_SHARED_ROUTE_PREFIXES = listOf(
            "/api/auth/",
            "/api/uploads",
            "/api/support/",
        )
        const val DRIVER_EKYC_PREFIX = "/ekyc/driver-verification/v1/"
        const val CUSTOMER_NEARBY_DRIVERS = "/api/location/customer/nearby-drivers"
        const val ADMIN_NEARBY_DRIVERS = "/api/location/nearby-drivers"
        val DRIVER_ONBOARDING_PREFIXES = listOf(
            "/api/driver/verification",
            "/api/driver/ekyc/",
        )
    }
}
