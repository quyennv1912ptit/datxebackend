package com.nhom15.datxebackend.auth.service

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseToken
import com.nhom15.datxebackend.admin.repository.AdminRepository
import com.nhom15.datxebackend.auth.dto.AuthLoginRequest
import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.auth.security.FirebaseAuthFilter
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.UserStatus
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.port.CustomerVehicleQueryPort
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.notification.token.DeviceTokenService
import com.nhom15.datxebackend.payment.service.WalletService
import com.nhom15.datxebackend.pricing.api.RideQuoteProvider
import com.nhom15.datxebackend.ride.dto.request.CreateRideRequest
import com.nhom15.datxebackend.ride.dto.request.EstimateRideRequest
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.RideCreateIdempotencyRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.RideCreationValidator
import com.nhom15.datxebackend.ride.service.RideEstimateService
import com.nhom15.datxebackend.ride.service.RideService
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.routing.service.RideRouteService
import com.nhom15.datxebackend.voucher.service.VoucherService
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.util.Optional

class CustomerSuspensionPolicyTests {

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun buildAuthService(
        firebaseAuth: FirebaseAuth = mock(FirebaseAuth::class.java),
        customerRepository: CustomerRepository = mock(CustomerRepository::class.java),
    ): AuthService = AuthService(
        firebaseAuth = firebaseAuth,
        customerRepository = customerRepository,
        driverRepository = mock(DriverRepository::class.java),
        adminRepository = mock(AdminRepository::class.java),
        deviceTokenService = mock(DeviceTokenService::class.java),
        authenticatedUserCache = AuthenticatedUserCache(ttlMillis = 60_000L),
    )

    @Test
    fun `soft suspended customer (INACTIVE) can login and authenticate`() {
        val firebaseAuth = mock(FirebaseAuth::class.java)
        val customerRepo = mock(CustomerRepository::class.java)
        val token = mock(FirebaseToken::class.java)
        `when`(token.uid).thenReturn("uid-soft")
        `when`(token.claims).thenReturn(mapOf("phone_number" to "+84901234567"))
        `when`(firebaseAuth.verifyIdToken("token-soft", true)).thenReturn(token)

        val inactiveCustomer = Customer().apply {
            id = 10L
            firebaseUid = "uid-soft"
            fullName = "Khach Tam Ngung"
            phoneNumber = "+84901234567"
            status = UserStatus.INACTIVE
        }
        `when`(customerRepo.findByFirebaseUid("uid-soft")).thenReturn(Optional.of(inactiveCustomer))
        `when`(customerRepo.save(inactiveCustomer)).thenReturn(inactiveCustomer)

        val authService = buildAuthService(firebaseAuth, customerRepo)
        val profile = authService.loginCustomer(AuthLoginRequest(idToken = "token-soft"))
        assertEquals("INACTIVE", profile.status)

        val authed = authService.findAuthenticatedUser("uid-soft", Role.CUSTOMER)
        assertNotNull(authed)
        assertEquals("INACTIVE", authed?.status)
    }

    @Test
    fun `hard suspended customer (BANNED) is blocked from login and immediately rejected with 403 by filter`() {
        val firebaseAuth = mock(FirebaseAuth::class.java)
        val customerRepo = mock(CustomerRepository::class.java)
        val token = mock(FirebaseToken::class.java)
        `when`(token.uid).thenReturn("uid-hard")
        `when`(token.claims).thenReturn(mapOf("phone_number" to "+84909999999"))
        `when`(firebaseAuth.verifyIdToken("token-hard", true)).thenReturn(token)

        val bannedCustomer = Customer().apply {
            id = 11L
            firebaseUid = "uid-hard"
            fullName = "Khach Bi Khoa"
            phoneNumber = "+84909999999"
            status = UserStatus.BANNED
        }
        `when`(customerRepo.findByFirebaseUid("uid-hard")).thenReturn(Optional.of(bannedCustomer))

        val authService = buildAuthService(firebaseAuth, customerRepo)
        val loginEx = assertThrows(AppException::class.java) {
            authService.loginCustomer(AuthLoginRequest(idToken = "token-hard"))
        }
        assertEquals(ErrorCode.ACCOUNT_LOCKED, loginEx.errorCode)
        assertNull(authService.findAuthenticatedUser("uid-hard", Role.CUSTOMER))
        assertTrue(authService.isCustomerBanned("uid-hard", Role.CUSTOMER))

        var lockedCallbackCalled = false
        val filter = FirebaseAuthFilter(firebaseAuth, authService) { res ->
            lockedCallbackCalled = true
            res.status = 403
        }
        val req = MockHttpServletRequest("GET", "/api/customer/rides").apply {
            addHeader("Authorization", "Bearer token-hard")
            addHeader("X-Role", "CUSTOMER")
        }
        val res = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(req, res, chain)
        assertTrue(lockedCallbackCalled)
        assertEquals(403, res.status)
        assertNull(SecurityContextHolder.getContext().authentication)
        verify(chain, never()).doFilter(req, res)
    }

    @Test
    fun `soft suspended customer (INACTIVE) is blocked from creating and estimating rides with ACCOUNT_SUSPENDED`() {
        val customerRepo = mock(CustomerRepository::class.java)
        val inactiveCustomer = Customer().apply {
            id = 12L
            firebaseUid = "uid-soft-ride"
            status = UserStatus.INACTIVE
        }
        `when`(customerRepo.findByIdForUpdate(12L)).thenReturn(Optional.of(inactiveCustomer))

        val rideService = RideService(
            rideRepository = mock(RideRepository::class.java),
            vehicleQueryPort = mock(CustomerVehicleQueryPort::class.java),
            quoteProvider = mock(RideQuoteProvider::class.java),
            rideRoutes = mock(RideRouteService::class.java),
            stateMachine = mock(RideStateMachine::class.java),
            voucherService = mock(VoucherService::class.java),
            creationValidator = mock(RideCreationValidator::class.java),
            customerRepository = customerRepo,
            idempotencyRepository = mock(RideCreateIdempotencyRepository::class.java),
            rateLimiter = mock(RedisRateLimiter::class.java),
            wallets = mock(WalletService::class.java),
        )
        val estimateService = RideEstimateService(
            quoteProvider = mock(RideQuoteProvider::class.java),
            rideRoutes = mock(RideRouteService::class.java),
            voucherService = mock(VoucherService::class.java),
            vehicleQueryPort = mock(CustomerVehicleQueryPort::class.java),
            rateLimiter = mock(RedisRateLimiter::class.java),
            creationValidator = mock(RideCreationValidator::class.java),
        )

        val actor = AuthUser(
            id = 12L,
            firebaseUid = "uid-soft-ride",
            role = Role.CUSTOMER,
            fullName = "Khach Tam Ngung",
            email = null,
            phoneNumber = "+84901234567",
            status = UserStatus.INACTIVE.name,
        )

        val createEx = assertThrows(AppException::class.java) {
            rideService.createRide(
                actor,
                CreateRideRequest(
                    vehicleId = 1L,
                    pickupLat = 21.0285,
                    pickupLng = 105.8542,
                    pickupAddress = "A",
                    dropoffLat = 21.03,
                    dropoffLng = 105.86,
                    dropoffAddress = "B",
                    type = RideType.INSTANT,
                    paymentMethod = PaymentMethod.CASH,
                ),
            )
        }
        assertEquals(ErrorCode.ACCOUNT_SUSPENDED, createEx.errorCode)

        val estimateEx = assertThrows(AppException::class.java) {
            estimateService.estimateRide(
                actor,
                EstimateRideRequest(
                    vehicleId = 1L,
                    pickupLat = 21.0285,
                    pickupLng = 105.8542,
                    pickupAddress = "A",
                    dropoffLat = 21.03,
                    dropoffLng = 105.86,
                    dropoffAddress = "B",
                    paymentMethod = PaymentMethod.CASH,
                ),
            )
        }
        assertEquals(ErrorCode.ACCOUNT_SUSPENDED, estimateEx.errorCode)
    }
}
