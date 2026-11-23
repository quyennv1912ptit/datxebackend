package com.nhom15.datxebackend.auth.service

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseToken
import com.nhom15.datxebackend.admin.repository.AdminRepository
import com.nhom15.datxebackend.auth.dto.AuthLoginRequest
import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.UserStatus
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.port.CustomerVehicleQueryPort
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.AuthenticatedUserProvider
import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.common.websocket.WsEnvelope
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.channel.StompChannel
import com.nhom15.datxebackend.notification.token.DeviceTokenRepository
import com.nhom15.datxebackend.notification.token.DeviceTokenService
import com.nhom15.datxebackend.notification.ws.FirebaseAuthTokenVerifier
import com.nhom15.datxebackend.notification.ws.StompPrincipal
import com.nhom15.datxebackend.payment.service.WalletService
import com.nhom15.datxebackend.pricing.api.RideQuoteProvider
import com.nhom15.datxebackend.ride.dto.request.CreateRideRequest
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.RideCreateIdempotencyRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.RideCreationValidator
import com.nhom15.datxebackend.ride.service.RideService
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.routing.service.RideRouteService
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import com.nhom15.datxebackend.voucher.service.VoucherService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.user.SimpSession
import org.springframework.messaging.simp.user.SimpSubscription
import org.springframework.messaging.simp.user.SimpUser
import org.springframework.messaging.simp.user.SimpUserRegistry
import java.time.Instant
import java.util.Optional

class AccountLifecycleBatch2ATests {

    @Test
    fun `F-023 deleting customer of dual-role account preserves sibling driver Firebase UID and login`() {
        val customerRepo = Mockito.mock(CustomerRepository::class.java)
        val driverRepo = Mockito.mock(DriverRepository::class.java)
        val deviceTokens = Mockito.mock(DeviceTokenRepository::class.java)
        val rideRepo = Mockito.mock(RideRepository::class.java)
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        val outbox = Mockito.mock(AccountCleanupOutboxRepository::class.java)
        val cache = AuthenticatedUserCache(ttlMillis = 3_000L)

        val sharedUid = "uid-shared-123"
        val customer = Customer().apply {
            id = 1L
            firebaseUid = sharedUid
            status = UserStatus.ACTIVE
        }
        val siblingDriver = Driver().apply {
            id = 10L
            firebaseUid = sharedUid
            status = DriverStatus.OFFLINE
        }

        Mockito.`when`(customerRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(customer))
        Mockito.`when`(customerRepo.findById(1L)).thenReturn(Optional.of(customer))
        Mockito.`when`(driverRepo.findByFirebaseUid(sharedUid)).thenReturn(Optional.of(siblingDriver))
        Mockito.`when`(rideRepo.existsByCustomerIdAndStatusNotIn(eq(1L), anyList())).thenReturn(false)
        Mockito.doReturn(emptyList<String>()).`when`(jdbc)
            .query(anyString(), any<RowMapper<String>>(), any())

        val deletionService = AccountDeletionService(
            customerRepository = customerRepo,
            driverRepository = driverRepo,
            deviceTokenRepository = deviceTokens,
            rideRepository = rideRepo,
            jdbcTemplate = jdbc,
            cleanupOutbox = outbox,
            authenticatedUserCache = cache,
        )

        val actor = AuthUser(1L, sharedUid, Role.CUSTOMER, "Dual", null, null, "ACTIVE")
        deletionService.deleteAccount(actor)

        // Must acquire FOR UPDATE lock on customer
        Mockito.verify(customerRepo).findByIdForUpdate(1L)
        // Must NOT enqueue Firebase UID deletion while sibling driver is still active
        Mockito.verify(outbox).enqueue("", "CUSTOMER", 1L, emptyList())

        // Defense-in-depth: AccountCleanupOutboxWorker must also refuse firebaseAuth.deleteUser if sibling is active
        val firebaseAuth = Mockito.mock(FirebaseAuth::class.java)
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        val cloudinary = Mockito.mock(CloudinaryAssetService::class.java)
        Mockito.`when`(outbox.claimDue()).thenReturn(
            listOf(AccountCleanupTask(1L, sharedUid, "CUSTOMER", 1L, emptyList())),
        )
        val worker = AccountCleanupOutboxWorker(outbox, firebaseAuth, redis, cloudinary, customerRepo, driverRepo)
        worker.processPendingCleanup()
        Mockito.verify(firebaseAuth, Mockito.never()).deleteUser(sharedUid)

        // Once sibling driver is also deleted (no active profile remains), Firebase user IS deleted
        Mockito.`when`(customerRepo.findByFirebaseUid(sharedUid)).thenReturn(Optional.empty())
        Mockito.`when`(driverRepo.findByFirebaseUid(sharedUid)).thenReturn(Optional.empty())
        Mockito.`when`(outbox.claimDue()).thenReturn(
            listOf(AccountCleanupTask(2L, sharedUid, "DRIVER", 10L, emptyList())),
        )
        worker.processPendingCleanup()
        Mockito.verify(firebaseAuth).deleteUser(sharedUid)
    }

    @Test
    fun `F-024 createRide rejects soft-deleted customer after acquiring FOR UPDATE lock`() {
        val rideRepo = Mockito.mock(RideRepository::class.java)
        val vehiclePort = Mockito.mock(CustomerVehicleQueryPort::class.java)
        val quoteProvider = Mockito.mock(RideQuoteProvider::class.java)
        val rideRoutes = Mockito.mock(RideRouteService::class.java)
        val stateMachine = Mockito.mock(RideStateMachine::class.java)
        val voucherService = Mockito.mock(VoucherService::class.java)
        val validator = Mockito.mock(RideCreationValidator::class.java)
        val customerRepo = Mockito.mock(CustomerRepository::class.java)
        val idempotencyRepo = Mockito.mock(RideCreateIdempotencyRepository::class.java)
        val rateLimiter = Mockito.mock(RedisRateLimiter::class.java)
        val wallets = Mockito.mock(WalletService::class.java)

        val deletedCustomer = Customer().apply {
            id = 1L
            firebaseUid = "deleted-customer-1"
            status = UserStatus.INACTIVE
            deletedAt = Instant.now()
        }
        Mockito.`when`(customerRepo.findByIdForUpdate(1L)).thenReturn(Optional.of(deletedCustomer))

        val rideService = RideService(
            rideRepository = rideRepo,
            vehicleQueryPort = vehiclePort,
            quoteProvider = quoteProvider,
            rideRoutes = rideRoutes,
            stateMachine = stateMachine,
            voucherService = voucherService,
            creationValidator = validator,
            customerRepository = customerRepo,
            idempotencyRepository = idempotencyRepo,
            rateLimiter = rateLimiter,
            wallets = wallets,
        )

        val actor = AuthUser(1L, "uid-1", Role.CUSTOMER, "Alice", null, null, "ACTIVE")
        val req = CreateRideRequest(
            vehicleId = 5L,
            type = RideType.INSTANT,
            pickupLat = 21.0285,
            pickupLng = 105.8000,
            pickupAddress = "A",
            dropoffLat = 21.0350,
            dropoffLng = 105.8100,
            dropoffAddress = "B",
            paymentMethod = PaymentMethod.CASH,
        )

        assertThatThrownBy { rideService.createRide(actor, req) }
            .isInstanceOf(AppException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.ACCOUNT_LOCKED)
        Mockito.verify(rideRepo, Mockito.never()).saveAndFlush(any())
    }

    @Test
    fun `F-024 deleteDriver locks FOR UPDATE, blocks when PENDING withdrawal exists, and retry is safe`() {
        val customerRepo = Mockito.mock(CustomerRepository::class.java)
        val driverRepo = Mockito.mock(DriverRepository::class.java)
        val deviceTokens = Mockito.mock(DeviceTokenRepository::class.java)
        val rideRepo = Mockito.mock(RideRepository::class.java)
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        val outbox = Mockito.mock(AccountCleanupOutboxRepository::class.java)
        val cache = AuthenticatedUserCache(ttlMillis = 3_000L)

        val driver = Driver().apply {
            id = 10L
            firebaseUid = "uid-drv-10"
            status = DriverStatus.OFFLINE
            bankAccountNumber = "1234567890"
        }
        Mockito.`when`(driverRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(driver))
        Mockito.`when`(driverRepo.findById(10L)).thenReturn(Optional.of(driver))
        Mockito.`when`(rideRepo.existsByDriverIdAndStatusNotIn(eq(10L), anyList())).thenReturn(false)
        Mockito.`when`(
            jdbc.queryForObject(contains("withdrawal_requests"), eq(Boolean::class.java), eq(10L)),
        ).thenReturn(true)

        val deletionService = AccountDeletionService(
            customerRepository = customerRepo,
            driverRepository = driverRepo,
            deviceTokenRepository = deviceTokens,
            rideRepository = rideRepo,
            jdbcTemplate = jdbc,
            cleanupOutbox = outbox,
            authenticatedUserCache = cache,
        )

        val actor = AuthUser(10L, "uid-drv-10", Role.DRIVER, "Driver 10", null, null, "OFFLINE")
        assertThatThrownBy { deletionService.deleteAccount(actor) }
            .isInstanceOf(ConflictException::class.java)

        Mockito.verify(driverRepo).findByIdForUpdate(10L)
        assertThat(driver.bankAccountNumber).isEqualTo("1234567890")
        assertThat(driver.deletedAt).isNull()
        Mockito.verify(outbox, Mockito.never()).enqueue(anyString(), anyString(), anyLong(), anyList())

        // Retry on already-deleted driver throws ACCOUNT_LOCKED without enqueuing
        driver.deletedAt = Instant.now()
        assertThatThrownBy { deletionService.deleteAccount(actor) }
            .isInstanceOf(AppException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.ACCOUNT_LOCKED)
    }

    @Test
    fun `F-025 attachDriverByPhone syncs eKYC owner_uid and evicts cache after commit`() {
        val firebaseAuth = Mockito.mock(FirebaseAuth::class.java)
        val customerRepo = Mockito.mock(CustomerRepository::class.java)
        val driverRepo = Mockito.mock(DriverRepository::class.java)
        val adminRepo = Mockito.mock(AdminRepository::class.java)
        val deviceTokens = Mockito.mock(DeviceTokenService::class.java)
        val cache = Mockito.mock(AuthenticatedUserCache::class.java)
        val jdbc = Mockito.mock(JdbcTemplate::class.java)

        val token = Mockito.mock(FirebaseToken::class.java)
        Mockito.`when`(token.uid).thenReturn("new-uid-2")
        Mockito.`when`(token.claims).thenReturn(mapOf("phone_number" to "+84901234567"))
        Mockito.`when`(firebaseAuth.verifyIdToken("valid-tok", true)).thenReturn(token)
        Mockito.`when`(adminRepo.existsByFirebaseUid("new-uid-2")).thenReturn(false)
        Mockito.`when`(driverRepo.findByFirebaseUid("new-uid-2")).thenReturn(Optional.empty())

        val existingDriver = Driver().apply {
            id = 10L
            firebaseUid = "old-uid-1"
            phoneNumber = "+84901234567"
            status = DriverStatus.OFFLINE
        }
        Mockito.`when`(driverRepo.findByPhoneNumber("+84901234567")).thenReturn(Optional.of(existingDriver))
        Mockito.`when`(driverRepo.save(existingDriver)).thenReturn(existingDriver)

        val authService = AuthService(
            firebaseAuth = firebaseAuth,
            customerRepository = customerRepo,
            driverRepository = driverRepo,
            adminRepository = adminRepo,
            deviceTokenService = deviceTokens,
            authenticatedUserCache = cache,
            jdbcTemplate = jdbc,
        )

        val profile = authService.loginDriver(AuthLoginRequest(idToken = "valid-tok", phoneNumber = "+84901234567"))

        assertThat(profile.firebaseUid).isEqualTo("new-uid-2")
        Mockito.verify(cache).evictAfterCommit("old-uid-1")
        Mockito.verify(cache, Mockito.never()).evict("old-uid-1")
        Mockito.verify(jdbc).update(contains("driver_verification_applications"), eq("new-uid-2"), eq("old-uid-1"))
        Mockito.verify(jdbc).update(contains("driver_verification_projection"), eq("new-uid-2"), eq("old-uid-1"))
    }

    @Test
    fun `F-016 StompChannel blocks outbound push to banned or deleted user with stale subscription`() {
        val userRegistry = Mockito.mock(SimpUserRegistry::class.java)
        val messaging = Mockito.mock(SimpMessagingTemplate::class.java)
        val firebaseAuth = Mockito.mock(FirebaseAuth::class.java)
        val userProvider = object : AuthenticatedUserProvider {
            override fun findAuthenticatedUser(firebaseUid: String, roleHint: Role?): AuthUser? = null
        }

        val stalePrincipal = StompPrincipal(Recipient.Customer(2L), firebaseUid = "uid-banned-2", role = Role.CUSTOMER)
        val sub = Mockito.mock(SimpSubscription::class.java)
        Mockito.`when`(sub.destination).thenReturn("/user/queue/trip")
        val session = Mockito.mock(SimpSession::class.java)
        Mockito.`when`(session.subscriptions).thenReturn(setOf(sub))
        val simpUser = Mockito.mock(SimpUser::class.java)
        Mockito.`when`(simpUser.principal).thenReturn(stalePrincipal)
        Mockito.`when`(simpUser.sessions).thenReturn(setOf(session))
        Mockito.`when`(userRegistry.getUser("CUSTOMER:2")).thenReturn(simpUser)

        val verifier = FirebaseAuthTokenVerifier(firebaseAuth, userProvider)
        val channel = StompChannel(userRegistry, messaging, verifier)
        val sent = channel.send(Recipient.Customer(2L), WsEnvelope(PayloadType.CHAT_MESSAGE, mapOf("rideId" to 99)))

        assertThat(sent).isFalse()
        Mockito.verify(messaging, Mockito.never()).convertAndSendToUser(anyString(), anyString(), any())
    }

    @Test
    fun `F-024 tryAssignDriver rejects soft-deleted driver after acquiring FOR UPDATE lock`() {
        val rideRepo = Mockito.mock(RideRepository::class.java)
        val driverRepo = Mockito.mock(DriverRepository::class.java)
        val ride = Mockito.mock(com.nhom15.datxebackend.ride.entity.Ride::class.java)
        Mockito.`when`(rideRepo.assignDriverIfSearching(99L, 10L)).thenReturn(1)
        Mockito.`when`(rideRepo.findById(99L)).thenReturn(Optional.of(ride))

        val deletedDriver = Driver().apply {
            id = 10L
            firebaseUid = "deleted-driver-10"
            status = DriverStatus.ONLINE
            deletedAt = Instant.now()
        }
        Mockito.`when`(driverRepo.findByIdForUpdate(10L)).thenReturn(Optional.of(deletedDriver))

        val stateMachine = RideStateMachine(
            rideRepository = rideRepo,
            historyRepository = Mockito.mock(com.nhom15.datxebackend.ride.repository.RideStatusHistoryRepository::class.java),
            eventPublisher = Mockito.mock(com.nhom15.datxebackend.common.event.EventPublisher::class.java),
            reportRepository = Mockito.mock(com.nhom15.datxebackend.ride.repository.VehicleHandoverReportRepository::class.java),
            conversationService = Mockito.mock(com.nhom15.datxebackend.chat.service.ConversationService::class.java),
            matching = com.nhom15.datxebackend.config.properties.MatchingProperties(),
            dispatchOutbox = Mockito.mock(com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository::class.java),
            dispatchOffers = Mockito.mock(com.nhom15.datxebackend.ride.repository.DispatchOfferRepository::class.java),
            drivers = driverRepo,
            wallets = Mockito.mock(WalletService::class.java),
            vouchers = Mockito.mock(VoucherService::class.java),
            scheduled = com.nhom15.datxebackend.config.properties.ScheduledRideProperties(),
            clock = java.time.Clock.systemUTC(),
            verification = Mockito.mock(com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility::class.java),
        )

        assertThatThrownBy { stateMachine.tryAssignDriver(99L, 10L) }
            .isInstanceOf(ConflictException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.DRIVER_NOT_AVAILABLE)
        Mockito.verify(driverRepo).findByIdForUpdate(10L)
        Mockito.verify(driverRepo, Mockito.never()).save(any())
    }
}
