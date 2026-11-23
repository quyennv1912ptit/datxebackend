package com.nhom15.datxebackend.auth.security

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseToken
import com.nhom15.datxebackend.auth.service.AuthService
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirebaseAuthFilterRoleTests {
    private val lookups = mutableListOf<List<Any?>>()
    private var customerResult: AuthUser? = null
    private var driverResult: AuthUser? = null
    private var adminResult: AuthUser? = null

    private val token = Mockito.mock(FirebaseToken::class.java, Answer<Any?> { invocation ->
        if (invocation.method.name == "getUid") "uid-1" else null
    })
    private val firebaseAuth = Mockito.mock(FirebaseAuth::class.java, Answer<Any?> { invocation ->
        if (invocation.method.name == "verifyIdToken" &&
            invocation.arguments[0] == "id-token" && invocation.arguments[1] == true
        ) {
            token
        } else {
            null
        }
    })
    private val authService = Mockito.mock(AuthService::class.java, Answer<Any?> { invocation ->
        lookups += invocation.arguments.toList()
        if (invocation.method.name == "findAuthenticatedUser") {
            // AuthService resolves an admin first when no role hint is supplied.
            when (invocation.arguments[1]) {
                Role.DRIVER -> driverResult
                Role.ADMIN -> adminResult
                Role.CUSTOMER -> customerResult
                else -> adminResult ?: customerResult
            }
        } else {
            null
        }
    })
    private val filter = FirebaseAuthFilter(firebaseAuth, authService)
    private val chain = FilterChain { _, _ -> Unit }

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.clearContext()
        lookups.clear()
        customerResult = null
        driverResult = null
        adminResult = null
    }

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    @Test
    fun `customer with X-Role DRIVER cannot reach a driver-only endpoint`() {
        // Customer-only identity: looking up a DRIVER profile must fail.
        filter.doFilter(request("/api/driver/offers", "DRIVER"), MockHttpServletResponse(), chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        assertEquals(listOf(listOf<Any?>("uid-1", Role.DRIVER)), lookups)
    }

    @Test
    fun `unverified dual-profile driver cannot reach a privileged driver endpoint`() {
        driverResult = AuthUser(
            id = 2, firebaseUid = "uid-1", role = Role.DRIVER,
            fullName = null, email = null, phoneNumber = null,
            status = "OFFLINE", verificationStatus = "PENDING",
        )

        filter.doFilter(request("/api/driver/rides", "DRIVER"), MockHttpServletResponse(), chain)

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `dual-profile driver is resolved by route when the client sends no X-Role`() {
        driverResult = AuthUser(
            id = 2, firebaseUid = "uid-1", role = Role.DRIVER,
            fullName = null, email = null, phoneNumber = null,
            status = "ONLINE", verificationStatus = "APPROVED",
        )

        filter.doFilter(request("/api/driver/offers", null), MockHttpServletResponse(), chain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertNotNull(authentication)
        assertEquals(Role.DRIVER, (authentication.principal as AuthUser).role)
        assertTrue(authentication.authorities.any { it.authority == "ROLE_DRIVER" })
        assertEquals(listOf(listOf<Any?>("uid-1", Role.DRIVER)), lookups)
    }

    @Test
    fun `X-Role on a role-scoped route cannot override the route role`() {
        customerResult = AuthUser(
            id = 1, firebaseUid = "uid-1", role = Role.CUSTOMER,
            fullName = null, email = null, phoneNumber = null, status = "ACTIVE",
        )

        filter.doFilter(request("/api/customer/profile", "DRIVER"), MockHttpServletResponse(), chain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertNotNull(authentication)
        assertEquals(Role.CUSTOMER, (authentication.principal as AuthUser).role)
        assertEquals(listOf(listOf<Any?>("uid-1", Role.CUSTOMER)), lookups)
    }

    @Test
    fun `X-Role ADMIN is rejected outside admin routes`() {
        filter.doFilter(request("/api/customer/profile", "ADMIN"), MockHttpServletResponse(), chain)

        assertNull(SecurityContextHolder.getContext().authentication)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `admin can resolve shared routes while sending X-Role ADMIN`() {
        adminResult = AuthUser(
            id = 3, firebaseUid = "uid-1", role = Role.ADMIN,
            fullName = null, email = null, phoneNumber = null, status = "ACTIVE",
        )

        listOf("/api/auth/me", "/api/uploads/signature", "/api/support/tickets").forEach { uri ->
            SecurityContextHolder.clearContext()
            lookups.clear()

            filter.doFilter(request(uri, "ADMIN"), MockHttpServletResponse(), chain)

            val authentication = SecurityContextHolder.getContext().authentication
            assertNotNull(authentication, uri)
            assertEquals(Role.ADMIN, (authentication.principal as AuthUser).role, uri)
            assertEquals(listOf(listOf<Any?>("uid-1", Role.ADMIN)), lookups, uri)
        }
    }

    @Test
    fun `onboarding paths stay available to an unverified driver`() {
        driverResult = AuthUser(
            id = 2, firebaseUid = "uid-1", role = Role.DRIVER,
            fullName = null, email = null, phoneNumber = null,
            status = "OFFLINE", verificationStatus = "PENDING",
        )

        filter.doFilter(
            request("/api/driver/ekyc/documents/cccd_front/extract", "DRIVER"),
            MockHttpServletResponse(),
            chain,
        )

        assertNotNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `admin can resolve on the dual-role verification me endpoint without X-Role`() {
        adminResult = AuthUser(
            id = 3, firebaseUid = "uid-1", role = Role.ADMIN,
            fullName = null, email = null, phoneNumber = null, status = "ACTIVE",
        )

        filter.doFilter(request("/ekyc/driver-verification/v1/me", null), MockHttpServletResponse(), chain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertNotNull(authentication)
        assertEquals(Role.ADMIN, (authentication.principal as AuthUser).role)
        assertEquals(
            listOf(listOf<Any?>("uid-1", Role.DRIVER), listOf<Any?>("uid-1", Role.ADMIN)),
            lookups,
        )
    }

    @Test
    fun `admin can select the admin profile on the dual-role verification me endpoint`() {
        adminResult = AuthUser(
            id = 3, firebaseUid = "uid-1", role = Role.ADMIN,
            fullName = null, email = null, phoneNumber = null, status = "ACTIVE",
        )

        filter.doFilter(request("/ekyc/driver-verification/v1/me", "ADMIN"), MockHttpServletResponse(), chain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertNotNull(authentication)
        assertEquals(Role.ADMIN, (authentication.principal as AuthUser).role)
        assertEquals(listOf(listOf<Any?>("uid-1", Role.ADMIN)), lookups)
    }

    @Test
    fun `dual-profile driver without X-Role can use the verification me endpoint`() {
        driverResult = AuthUser(
            id = 2, firebaseUid = "uid-1", role = Role.DRIVER,
            fullName = null, email = null, phoneNumber = null,
            status = "ONLINE", verificationStatus = "APPROVED",
        )
        customerResult = AuthUser(
            id = 1, firebaseUid = "uid-1", role = Role.CUSTOMER,
            fullName = null, email = null, phoneNumber = null, status = "ACTIVE",
        )

        filter.doFilter(request("/ekyc/driver-verification/v1/me", null), MockHttpServletResponse(), chain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertNotNull(authentication)
        assertEquals(Role.DRIVER, (authentication.principal as AuthUser).role)
        assertEquals(listOf(listOf<Any?>("uid-1", Role.DRIVER)), lookups)
    }

    @Test
    fun `admin can select the admin profile on the verification me alias without a role lookup race`() {
        adminResult = AuthUser(
            id = 3, firebaseUid = "uid-1", role = Role.ADMIN,
            fullName = null, email = null, phoneNumber = null, status = "ACTIVE",
        )

        filter.doFilter(request("/api/driver/verification/v1/me", "ADMIN"), MockHttpServletResponse(), chain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertNotNull(authentication)
        assertEquals(Role.ADMIN, (authentication.principal as AuthUser).role)
        assertEquals(listOf(listOf<Any?>("uid-1", Role.ADMIN)), lookups)
    }

    @Test
    fun `dual-profile driver without X-Role can use the verification me alias`() {
        driverResult = AuthUser(
            id = 2, firebaseUid = "uid-1", role = Role.DRIVER,
            fullName = null, email = null, phoneNumber = null,
            status = "ONLINE", verificationStatus = "APPROVED",
        )
        customerResult = AuthUser(
            id = 1, firebaseUid = "uid-1", role = Role.CUSTOMER,
            fullName = null, email = null, phoneNumber = null, status = "ACTIVE",
        )

        filter.doFilter(request("/api/driver/verification/v1/me", null), MockHttpServletResponse(), chain)

        val authentication = SecurityContextHolder.getContext().authentication
        assertNotNull(authentication)
        assertEquals(Role.DRIVER, (authentication.principal as AuthUser).role)
        assertEquals(listOf(listOf<Any?>("uid-1", Role.DRIVER)), lookups)
    }

    private fun request(uri: String, roleHeader: String?): HttpServletRequest =
        Mockito.mock(HttpServletRequest::class.java, Answer<Any?> { invocation ->
            when (invocation.method.name) {
                "getHeader" -> when (invocation.arguments[0]) {
                    "Authorization" -> "Bearer id-token"
                    "X-Role" -> roleHeader
                    else -> null
                }
                "getRequestURI" -> uri
                else -> null
            }
        })
}
