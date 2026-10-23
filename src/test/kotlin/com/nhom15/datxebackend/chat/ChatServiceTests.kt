package com.nhom15.datxebackend.chat

import com.nhom15.datxebackend.chat.dto.SendMessageRequest
import com.nhom15.datxebackend.chat.entity.ChatMessage
import com.nhom15.datxebackend.chat.entity.Conversation
import com.nhom15.datxebackend.chat.event.ChatMessagesRead
import com.nhom15.datxebackend.chat.repository.ChatMessageRepository
import com.nhom15.datxebackend.chat.repository.ChatMessageWriter
import com.nhom15.datxebackend.chat.repository.ConversationRepository
import com.nhom15.datxebackend.chat.service.ChatRateLimiter
import com.nhom15.datxebackend.chat.service.ChatService
import com.nhom15.datxebackend.chat.service.ConversationService
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.time.Clock
import java.util.Optional
import java.util.UUID

class ChatServiceTests {
    private val rideRepository = Mockito.mock(RideRepository::class.java)
    private val conversationRepository = Mockito.mock(ConversationRepository::class.java)
    private val messageRepository = Mockito.mock(ChatMessageRepository::class.java)
    private val messageWriter = Mockito.mock(ChatMessageWriter::class.java)
    private val rateLimiter = Mockito.mock(ChatRateLimiter::class.java)
    private val cloudinaryAssetService = Mockito.mock(CloudinaryAssetService::class.java)
    private val jdbcTemplate = Mockito.mock(JdbcTemplate::class.java)
    private val eventPublisher = Mockito.mock(ApplicationEventPublisher::class.java)
    private val now = Instant.parse("2026-10-23T10:00:00Z")
    private val conversationService = ConversationService(conversationRepository, rideRepository, jdbcTemplate)
    private val service = ChatService(
        conversationService,
        conversationRepository,
        messageRepository,
        messageWriter,
        rateLimiter,
        cloudinaryAssetService,
        rideRepository,
        jdbcTemplate,
        eventPublisher,
        Clock.fixed(now, java.time.ZoneOffset.UTC),
    )

    private val rideId = 77L
    private val customerId = 5L
    private val driverId = 5L
    private val conversationId = 88L
    private val customer = AuthUser(customerId, "customer-uid", Role.CUSTOMER, "Khách", null, null, "ACTIVE")
    private val driver = AuthUser(driverId, "driver-uid", Role.DRIVER, "Tài xế", null, null, "ACTIVE")
    private lateinit var ride: Ride
    private lateinit var conversation: Conversation

    @BeforeEach
    fun setUp() {
        ride = Ride(
            customerId = customerId,
            driverId = driverId,
            vehicleId = 9,
            status = RideStatus.DRIVER_ASSIGNED,
            type = RideType.INSTANT,
            pickup = Location(),
            dropoff = Location(),
            vehicleType = VehicleType.CAR,
            transmission = Transmission.AUTO,
            fareEstimate = 1000,
            paymentMethod = PaymentMethod.CASH,
        ).apply { id = rideId }
        conversation = Conversation().apply {
            id = conversationId
            this.rideId = this@ChatServiceTests.rideId
            this.customerId = this@ChatServiceTests.customerId
            this.driverId = this@ChatServiceTests.driverId
        }
        Mockito.`when`(rideRepository.findById(rideId)).thenReturn(Optional.of(ride))
        Mockito.`when`(rideRepository.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride))
        Mockito.`when`(conversationRepository.findByRideId(rideId)).thenReturn(conversation)
    }

    @Test
    fun `a non participant gets not found`() {
        val outsider = customer.copy(id = 90L)
        assertThrows(NotFoundException::class.java) {
            service.messages(rideId, outsider, null, null, 30)
        }
        Mockito.verifyNoInteractions(messageRepository)
    }

    @Test
    fun `closed ride rejects sending`() {
        ride.status = RideStatus.COMPLETED
        val error = assertThrows(AppException::class.java) {
            service.send(rideId, customer, SendMessageRequest(UUID.randomUUID(), content = "Xin chào"))
        }
        assertEquals("CHAT_CLOSED", error.errorCode.name)
        Mockito.verifyNoInteractions(rateLimiter, messageWriter, eventPublisher)
    }

    @Test
    fun `duplicate client id returns the stored message without publishing`() {
        val clientMessageId = UUID.randomUUID()
        val stored = message(101L, clientMessageId, Role.CUSTOMER)
        Mockito.`when`(messageRepository.findByConversationIdAndClientMessageId(conversationId, clientMessageId))
            .thenReturn(null, stored)
        Mockito.`when`(
            messageWriter.insertIfAbsent(
                conversationId, clientMessageId, Role.CUSTOMER, customerId, "TEXT", "Xin chào", null, null,
            ),
        ).thenReturn(null)

        val result = service.send(rideId, customer, SendMessageRequest(clientMessageId, content = "Xin chào"))

        assertTrue(result.duplicated)
        assertEquals(stored.id, result.message.id)
        Mockito.verify(messageWriter).insertIfAbsent(
            conversationId, clientMessageId, Role.CUSTOMER, customerId, "TEXT", "Xin chào", null, null,
        )
        Mockito.verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `after id page uses increasing message ids`() {
        val nextMessages = listOf(
            message(105L, UUID.randomUUID(), Role.DRIVER),
            message(106L, UUID.randomUUID(), Role.DRIVER),
        )
        Mockito.`when`(messageRepository.after(conversationId, 104L, PageRequest.of(0, 3)))
            .thenReturn(nextMessages)

        val result = service.messages(rideId, customer, beforeId = null, afterId = 104L, size = 2)

        assertEquals(listOf(105L, 106L), result.messages.map { it.id })
        assertFalse(result.hasMore)
        Mockito.verify(messageRepository).after(conversationId, 104L, PageRequest.of(0, 3))
        Mockito.verifyNoMoreInteractions(messageRepository)
    }

    @Test
    fun `mark read only updates messages from the other role up to requested id`() {
        Mockito.`when`(messageRepository.markRead(conversationId, "CUSTOMER", 105L, now))
            .thenReturn(2)

        val result = service.markRead(rideId, customer, 105L)

        assertEquals(2, result.markedCount)
        Mockito.verify(messageRepository).markRead(conversationId, "CUSTOMER", 105L, now)
        Mockito.verify(eventPublisher).publishEvent(ChatMessagesRead(rideId, 105L, Recipient.Driver(driverId)))
    }

    @Test
    fun `customer and driver with the same numeric id have different websocket principals`() {
        assertEquals("CUSTOMER:5", Recipient.Customer(5).key)
        assertEquals("DRIVER:5", Recipient.Driver(5).key)
        assertFalse(Recipient.Customer(5).key == Recipient.Driver(5).key)
    }

    private fun message(id: Long, clientId: UUID, sender: Role) = ChatMessage().apply {
        this.id = id
        conversationId = this@ChatServiceTests.conversationId
        senderRole = sender.name
        senderCustomerId = if (sender == Role.CUSTOMER) customerId else null
        senderDriverId = if (sender == Role.DRIVER) driverId else null
        messageType = "TEXT"
        content = "Tin $id"
        clientMessageId = clientId
        createdAt = Instant.parse("2026-10-23T10:00:00Z").plusSeconds(id)
    }
}
