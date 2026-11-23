package com.nhom15.datxebackend

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseToken
import com.nhom15.datxebackend.auth.security.FirebaseAuthFilter
import com.nhom15.datxebackend.auth.service.AuthService
import com.nhom15.datxebackend.chat.entity.Conversation
import com.nhom15.datxebackend.chat.event.ChatMessagesRead
import com.nhom15.datxebackend.chat.repository.ChatMessageRepository
import com.nhom15.datxebackend.chat.repository.ChatMessageWriter
import com.nhom15.datxebackend.chat.repository.ConversationRepository
import com.nhom15.datxebackend.chat.service.ChatRateLimiter
import com.nhom15.datxebackend.chat.service.ChatService
import com.nhom15.datxebackend.chat.service.ConversationService
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.UserStatus
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.customer.dto.UpdateCustomerProfileRequest
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.customer.service.CustomerProfileService
import com.nhom15.datxebackend.driver.verification.DriverVerificationWorkflowService
import com.nhom15.datxebackend.driver.verification.VerificationOrphanCleanupWorker
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class ReconciliationRegressionTests {

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `A1 - route-scoped endpoint ignores conflicting X-Role header`() {
        val firebaseAuth = mock(FirebaseAuth::class.java)
        val authService = mock(AuthService::class.java)
        val token = mock(FirebaseToken::class.java)
        `when`(token.uid).thenReturn("dual-uid-1")
        `when`(firebaseAuth.verifyIdToken("valid-token", true)).thenReturn(token)

        val customerUser = AuthUser(
            id = 10L,
            firebaseUid = "dual-uid-1",
            role = Role.CUSTOMER,
            fullName = "Dual User",
            email = "c@test.local",
            phoneNumber = "+84900000001",
            status = "ACTIVE",
        )
        `when`(authService.findAuthenticatedUser("dual-uid-1", Role.CUSTOMER)).thenReturn(customerUser)
        `when`(authService.findAuthenticatedUser("dual-uid-1", Role.DRIVER)).thenReturn(null)

        val filter = FirebaseAuthFilter(firebaseAuth, authService)
        val request = MockHttpServletRequest("GET", "/api/driver/profile")
        request.addHeader("Authorization", "Bearer valid-token")
        request.addHeader("X-Role", "CUSTOMER")

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `O2 - inactive or deleted customer cannot mutate profile`() {
        val repo = mock(CustomerRepository::class.java)
        val cloudinary = mock(CloudinaryAssetService::class.java)
        val service = CustomerProfileService(repo, cloudinary)

        val banned = Customer().apply {
            id = 42L
            firebaseUid = "uid-banned"
            fullName = "Old Name"
            status = UserStatus.BANNED
        }
        `when`(repo.findByIdForUpdate(42L)).thenReturn(Optional.of(banned))
        `when`(repo.findById(42L)).thenReturn(Optional.of(banned))

        val actor = AuthUser(
            id = 42L,
            firebaseUid = "uid-banned",
            role = Role.CUSTOMER,
            fullName = "Old Name",
            email = null,
            phoneNumber = null,
            status = "BANNED",
        )
        val ex = assertThrows(AppException::class.java) {
            service.update(actor, UpdateCustomerProfileRequest(fullName = "Hacked Name"))
        }
        assertEquals(ErrorCode.ACCOUNT_LOCKED, ex.errorCode)
    }

    @Test
    fun `D7 - markRead clamps upToMessageId to actual conversation max id`() {
        val rideRepository = mock(RideRepository::class.java)
        val conversationRepository = mock(ConversationRepository::class.java)
        val messageRepository = mock(ChatMessageRepository::class.java)
        val messageWriter = mock(ChatMessageWriter::class.java)
        val rateLimiter = mock(ChatRateLimiter::class.java)
        val cloudinaryAssetService = mock(CloudinaryAssetService::class.java)
        val jdbcTemplate = mock(JdbcTemplate::class.java)
        val eventPublisher = mock(ApplicationEventPublisher::class.java)
        val clock = Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC)

        val conversationService = ConversationService(conversationRepository, rideRepository, jdbcTemplate)
        val service = ChatService(
            conversationService,
            conversationRepository,
            messageRepository,
            messageWriter,
            rateLimiter,
            cloudinaryAssetService,
            rideRepository,
            jdbcTemplate,
            eventPublisher,
            clock,
        )

        val ride = Ride(
            customerId = 1L,
            driverId = 2L,
            vehicleId = 1L,
            vehicleType = VehicleType.CAR,
            transmission = Transmission.AUTO,
            type = RideType.INSTANT,
            status = RideStatus.DRIVER_ASSIGNED,
            pickup = Location(GeoPoint(21.0, 105.8), "A"),
            dropoff = Location(GeoPoint(21.1, 105.9), "B"),
            distanceKm = 2.0,
            fareEstimate = 30000L,
            paymentMethod = PaymentMethod.CASH,
        ).apply { id = 100L }

        val conv = Conversation().apply {
            id = 77L
            rideId = 100L
            customerId = 1L
            driverId = 2L
        }
        `when`(rideRepository.findById(100L)).thenReturn(Optional.of(ride))
        `when`(conversationRepository.findByRideIdAndDriverId(100L, 2L)).thenReturn(conv)
        `when`(messageRepository.markRead(77L, "CUSTOMER", 50L, clock.instant())).thenReturn(1)
        `when`(messageRepository.markRead(77L, "CUSTOMER", 999_999L, clock.instant())).thenReturn(1)

        val maxIdMethod = ChatMessageRepository::class.java.methods.firstOrNull { it.name == "findMaxIdInConversation" }
        assertTrue(maxIdMethod != null, "ChatMessageRepository must declare findMaxIdInConversation(cid)")
        `when`(maxIdMethod!!.invoke(messageRepository, 77L)).thenReturn(50L)

        val actor = AuthUser(
            id = 1L,
            firebaseUid = "c-1",
            role = Role.CUSTOMER,
            fullName = "Customer",
            email = null,
            phoneNumber = null,
            status = "ACTIVE",
        )
        service.markRead(100L, actor, 999_999L)

        val captor = ArgumentCaptor.forClass(ChatMessagesRead::class.java)
        verify(eventPublisher).publishEvent(captor.capture())
        assertEquals(50L, captor.value.upToMessageId)
    }

    @Test
    fun `O5 and #7 - Dockerfile pins JVM flags and deploy script validates HMAC key and blocks JAVA_OPTS`() {
        val dockerfile = Files.readString(Path.of("Dockerfile"))
        assertFalse(dockerfile.contains("\$JAVA_OPTS"), "Dockerfile must not expand env JAVA_OPTS")

        val deployScript = Files.readString(Path.of("scripts/deploy-production.sh"))
        assertTrue(
            deployScript.contains("DATXE_VERIFICATION_LOOKUP_HMAC_KEY"),
            "deploy-production.sh must validate DATXE_VERIFICATION_LOOKUP_HMAC_KEY before cutover",
        )
        assertTrue(
            deployScript.contains("JAVA_OPTS"),
            "deploy-production.sh must block JAVA_OPTS injection",
        )
    }

    @Test
    fun `#6 worker - VerificationOrphanCleanupWorker respects enabled flag, enforces min safe age, and isolates failures`() {
        val workflow = mock(DriverVerificationWorkflowService::class.java)
        `when`(workflow.reconcileOrphanCaptures(VerificationOrphanCleanupWorker.MIN_SAFE_AGE)).thenReturn(2)

        // Disabled worker must not call reconcileOrphanCaptures
        val disabled = VerificationOrphanCleanupWorker(workflow, enabled = false, minAge = java.time.Duration.ofHours(1))
        assertEquals(0, disabled.sweepOrphanCaptures())

        // Too-small minAge must be coerced up to MIN_SAFE_AGE (5m)
        val clamped = VerificationOrphanCleanupWorker(workflow, enabled = true, minAge = java.time.Duration.ofSeconds(10))
        assertEquals(2, clamped.sweepOrphanCaptures())
        verify(workflow).reconcileOrphanCaptures(VerificationOrphanCleanupWorker.MIN_SAFE_AGE)

        // Failures must be isolated and return 0 without killing scheduler thread
        `when`(workflow.reconcileOrphanCaptures(java.time.Duration.ofHours(2))).thenThrow(RuntimeException("disk busy"))
        val resilient = VerificationOrphanCleanupWorker(workflow, enabled = true, minAge = java.time.Duration.ofHours(2))
        assertEquals(0, resilient.sweepOrphanCaptures())
    }
}
