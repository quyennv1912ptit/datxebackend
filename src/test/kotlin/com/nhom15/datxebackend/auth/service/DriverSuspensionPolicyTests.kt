package com.nhom15.datxebackend.auth.service

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseToken
import com.nhom15.datxebackend.admin.repository.AdminDriverQueryRepository
import com.nhom15.datxebackend.admin.repository.AdminRepository
import com.nhom15.datxebackend.admin.service.AdminDriverService
import com.nhom15.datxebackend.auth.dto.AuthLoginRequest
import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.auth.security.FirebaseAuthFilter
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.dto.AdminDriverSummary
import com.nhom15.datxebackend.driver.dto.UpdateDriverAccountStatusRequest
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility
import com.nhom15.datxebackend.driver.verification.VerificationProjectionRepository
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.DriverAccountNotificationPayload
import com.nhom15.datxebackend.notification.token.DeviceTokenService
import com.nhom15.datxebackend.payment.entity.WalletType
import com.nhom15.datxebackend.payment.repository.WalletTransactionRepository
import com.nhom15.datxebackend.payment.repository.WithdrawalRepository
import com.nhom15.datxebackend.payment.service.WalletService
import com.nhom15.datxebackend.payment.service.WithdrawalService
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.tracking.service.DriverPresenceService
import com.nhom15.datxebackend.tracking.service.LocationService
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Instant
import java.util.Optional

class DriverSuspensionPolicyTests {

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    private fun buildAuthService(
        firebaseAuth: FirebaseAuth = mock(FirebaseAuth::class.java),
        driverRepository: DriverRepository = mock(DriverRepository::class.java),
    ): AuthService = AuthService(
        firebaseAuth = firebaseAuth,
        customerRepository = mock(CustomerRepository::class.java),
        driverRepository = driverRepository,
        adminRepository = mock(AdminRepository::class.java),
        deviceTokenService = mock(DeviceTokenService::class.java),
        authenticatedUserCache = AuthenticatedUserCache(0L),
    )

    private fun driverEntity(
        id: Long = 41L,
        uid: String = "drv-uid",
        status: DriverStatus = DriverStatus.OFFLINE,
        accountStatus: String = "ACTIVE",
        reason: String? = null,
        withdrawalFrozen: Boolean = false,
    ): Driver = Driver().apply {
        this.id = id
        this.firebaseUid = uid
        this.fullName = "Tài xế Test"
        this.phoneNumber = "+84901234567"
        this.status = status
        this.accountStatus = accountStatus
        this.accountStatusReason = reason
        this.withdrawalFrozen = withdrawalFrozen
        this.bankName = "Vietcombank"
        this.bankAccountNumber = "0123456789"
    }

    @Test
    fun `soft suspended driver can still login and authenticate`() {
        val firebaseAuth = mock(FirebaseAuth::class.java)
        val token = mock(FirebaseToken::class.java)
        val driverRepo = mock(DriverRepository::class.java)
        val driver = driverEntity(accountStatus = "SUSPENDED", reason = "GPLX hết hạn")

        `when`(firebaseAuth.verifyIdToken("valid-token", true)).thenReturn(token)
        `when`(token.uid).thenReturn("drv-uid")
        `when`(token.claims).thenReturn(emptyMap())
        `when`(driverRepo.findByFirebaseUid("drv-uid")).thenReturn(Optional.of(driver))
        `when`(driverRepo.save(driver)).thenReturn(driver)

        val authService = buildAuthService(firebaseAuth, driverRepo)
        val profile = authService.loginDriver(AuthLoginRequest(idToken = "valid-token"))

        assertEquals("SUSPENDED", profile.accountStatus)
        assertEquals("GPLX hết hạn", profile.accountStatusReason)
        assertNotNull(authService.findAuthenticatedUser("drv-uid", Role.DRIVER))
    }

    @Test
    fun `hard banned driver is blocked at login and returns 403 ACCOUNT_LOCKED in filter when not on active ride`() {
        val firebaseAuth = mock(FirebaseAuth::class.java)
        val token = mock(FirebaseToken::class.java)
        val driverRepo = mock(DriverRepository::class.java)
        val driver = driverEntity(status = DriverStatus.OFFLINE, accountStatus = "BANNED", reason = "Gian lận GPS")

        `when`(firebaseAuth.verifyIdToken("valid-token", true)).thenReturn(token)
        `when`(token.uid).thenReturn("drv-uid")
        `when`(token.claims).thenReturn(emptyMap())
        `when`(driverRepo.findByFirebaseUid("drv-uid")).thenReturn(Optional.of(driver))

        val authService = buildAuthService(firebaseAuth, driverRepo)
        val ex = assertThrows(AppException::class.java) {
            authService.loginDriver(AuthLoginRequest(idToken = "valid-token"))
        }
        assertEquals(ErrorCode.ACCOUNT_LOCKED, ex.errorCode)
        assertEquals("Gian lận GPS", ex.message)
        assertNull(authService.findAuthenticatedUser("drv-uid", Role.DRIVER))

        var capturedReason: String? = null
        val filter = FirebaseAuthFilter(firebaseAuth, authService) { res, reason ->
            capturedReason = reason
            res.status = 403
        }
        val req = MockHttpServletRequest("GET", "/api/driver/profile").apply {
            addHeader("Authorization", "Bearer valid-token")
            addHeader("X-Role", "DRIVER")
        }
        val res = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(req, res, chain)

        assertEquals(403, res.status)
        assertEquals("Gian lận GPS", capturedReason)
        verify(chain, never()).doFilter(req, res)
    }

    @Test
    fun `banned driver on active BUSY ride stays authenticated until ride completes`() {
        val driverRepo = mock(DriverRepository::class.java)
        val driver = driverEntity(status = DriverStatus.BUSY, accountStatus = "BANNED", reason = "Vi phạm nghiêm trọng")
        `when`(driverRepo.findByFirebaseUid("drv-uid")).thenReturn(Optional.of(driver))

        val authService = buildAuthService(driverRepository = driverRepo)
        val user = authService.findAuthenticatedUser("drv-uid", Role.DRIVER)

        assertNotNull(user)
        assertEquals("BANNED", user?.accountStatus)
        assertNull(authService.bannedAccountReason("drv-uid", Role.DRIVER))
    }

    @Test
    fun `soft suspended driver is blocked from going online with DRIVER_SUSPENDED`() {
        val driverRepo = mock(DriverRepository::class.java)
        val driver = driverEntity(status = DriverStatus.OFFLINE, accountStatus = "SUSPENDED", reason = "Tỷ lệ hủy cao")
        `when`(driverRepo.findByIdForUpdate(41L)).thenReturn(Optional.of(driver))

        val presenceService = DriverPresenceService(
            driverRepository = driverRepo,
            locationService = mock(LocationService::class.java),
            redisTemplate = mock(StringRedisTemplate::class.java),
            wallets = mock(WalletService::class.java),
            verification = DriverVerificationEligibility(mock(VerificationProjectionRepository::class.java)),
        )

        val ex = assertThrows(AppException::class.java) {
            presenceService.updateAvailability(41L, online = true, vehicleType = VehicleType.CAR)
        }
        assertEquals(ErrorCode.DRIVER_SUSPENDED, ex.errorCode)
        assertTrue(ex.message?.contains("Tỷ lệ hủy cao") == true)
    }

    @Test
    fun `admin suspending ONLINE driver forces offline immediately and notifies driver`() {
        val driverRepo = mock(DriverRepository::class.java)
        val queryRepo = mock(AdminDriverQueryRepository::class.java)
        val locationService = mock(LocationService::class.java)
        val notificationPort = mock(NotificationPort::class.java)
        val rideRepo = mock(RideRepository::class.java)
        val firebaseAuth = mock(FirebaseAuth::class.java)

        val driver = driverEntity(status = DriverStatus.ONLINE, accountStatus = "ACTIVE")
        `when`(driverRepo.findByIdForUpdate(41L)).thenReturn(Optional.of(driver))
        `when`(driverRepo.save(driver)).thenReturn(driver)
        `when`(rideRepo.existsByDriverIdAndStatusIn(41L, ACTIVE_RIDE_STATUSES)).thenReturn(false)
        `when`(queryRepo.findById(41L)).thenReturn(
            AdminDriverSummary(
                id = 41L, fullName = "Tài xế Test", phoneNumber = "+84901234567", email = null,
                status = "OFFLINE", accountStatus = "SUSPENDED", accountStatusReason = "GPLX hết hạn",
                withdrawalFrozen = false, verificationStatus = "APPROVED", flagged = false,
                totalTrips = 10, averageRating = 4.9, acceptanceRate = 0.9, completionRate = 0.9,
                cancelRate30d = 0.05, creditBalance = 100_000, incomeBalance = 500_000, createdAt = Instant.now(),
            ),
        )

        val service = AdminDriverService(
            queryRepository = queryRepo,
            skillRepository = mock(DriverSkillRepository::class.java),
            driverRepository = driverRepo,
            locationService = locationService,
            notificationPort = notificationPort,
            rideRepository = rideRepo,
            firebaseAuth = firebaseAuth,
        )
        val admin = AuthUser(1L, "admin-uid", Role.ADMIN, "Admin", "a@b.c", null, "ACTIVE")

        service.updateAccountStatus(admin, 41L, UpdateDriverAccountStatusRequest("SUSPENDED", "GPLX hết hạn", false))

        assertEquals(DriverStatus.OFFLINE, driver.status)
        assertEquals("SUSPENDED", driver.accountStatus)
        verify(locationService).removeDriver(41L)
        verify(notificationPort).send(
            Recipient.Driver(41L),
            DriverAccountNotificationPayload(
                type = PayloadType.DRIVER_FORCE_OFFLINE,
                status = "OFFLINE",
                reason = "GPLX hết hạn",
                accountStatus = "SUSPENDED",
                withdrawalFrozen = false,
            ),
        )
        verify(firebaseAuth, never()).revokeRefreshTokens("drv-uid")
    }

    @Test
    fun `admin banning BUSY driver defers force-offline and token revocation until ride completes`() {
        val driverRepo = mock(DriverRepository::class.java)
        val queryRepo = mock(AdminDriverQueryRepository::class.java)
        val locationService = mock(LocationService::class.java)
        val notificationPort = mock(NotificationPort::class.java)
        val rideRepo = mock(RideRepository::class.java)
        val firebaseAuth = mock(FirebaseAuth::class.java)

        val driver = driverEntity(status = DriverStatus.BUSY, accountStatus = "ACTIVE")
        `when`(driverRepo.findByIdForUpdate(41L)).thenReturn(Optional.of(driver))
        `when`(driverRepo.save(driver)).thenReturn(driver)
        `when`(rideRepo.existsByDriverIdAndStatusIn(41L, ACTIVE_RIDE_STATUSES)).thenReturn(true)
        `when`(queryRepo.findById(41L)).thenReturn(
            AdminDriverSummary(
                id = 41L, fullName = "Tài xế Test", phoneNumber = "+84901234567", email = null,
                status = "BUSY", accountStatus = "BANNED", accountStatusReason = "Gian lận",
                withdrawalFrozen = true, verificationStatus = "APPROVED", flagged = false,
                totalTrips = 10, averageRating = 4.9, acceptanceRate = 0.9, completionRate = 0.9,
                cancelRate30d = 0.05, creditBalance = 100_000, incomeBalance = 500_000, createdAt = Instant.now(),
            ),
        )

        val service = AdminDriverService(
            queryRepository = queryRepo,
            skillRepository = mock(DriverSkillRepository::class.java),
            driverRepository = driverRepo,
            locationService = locationService,
            notificationPort = notificationPort,
            rideRepository = rideRepo,
            firebaseAuth = firebaseAuth,
        )
        val admin = AuthUser(1L, "admin-uid", Role.ADMIN, "Admin", "a@b.c", null, "ACTIVE")

        service.updateAccountStatus(admin, 41L, UpdateDriverAccountStatusRequest("BANNED", "Gian lận", true))

        assertEquals(DriverStatus.BUSY, driver.status)
        assertEquals("BANNED", driver.accountStatus)
        assertTrue(driver.withdrawalFrozen)
        verify(locationService, never()).removeDriver(41L)
        verify(firebaseAuth, never()).revokeRefreshTokens("drv-uid")
    }

    @Test
    fun `withdrawal frozen blocks creating withdrawal request`() {
        val driverRepo = mock(DriverRepository::class.java)
        val withdrawalRepo = mock(WithdrawalRepository::class.java)
        val driver = driverEntity(accountStatus = "SUSPENDED", withdrawalFrozen = true)
        `when`(driverRepo.findByIdForUpdate(41L)).thenReturn(Optional.of(driver))
        `when`(withdrawalRepo.findByDriverIdAndIdempotencyKey(41L, "idem-1")).thenReturn(null)

        val service = WithdrawalService(
            requests = withdrawalRepo,
            transactions = mock(WalletTransactionRepository::class.java),
            drivers = driverRepo,
            wallets = mock(WalletService::class.java),
            events = mock(ApplicationEventPublisher::class.java),
        )

        val ex = assertThrows(AppException::class.java) {
            service.create(41L, 100_000L, "idem-1", WalletType.CASH)
        }
        assertEquals(ErrorCode.WITHDRAWAL_FROZEN, ex.errorCode)
        verify(withdrawalRepo).findByDriverIdAndIdempotencyKey(41L, "idem-1")
    }

    @Test
    fun `eligibility blocks suspended driver from going online and transitions statusAfterRide to OFFLINE`() {
        val eligibility = DriverVerificationEligibility(mock(VerificationProjectionRepository::class.java))
        val driver = driverEntity(status = DriverStatus.BUSY, accountStatus = "SUSPENDED").apply {
            onlineVehicleType = VehicleType.CAR
        }

        assertFalse(eligibility.mayGoOnline(driver, VehicleType.CAR))
        assertEquals(DriverStatus.OFFLINE, eligibility.statusAfterRide(driver))
        assertNull(driver.onlineVehicleType)
    }
}
