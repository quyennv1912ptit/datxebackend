package com.nhom15.datxebackend.chat

import com.nhom15.datxebackend.chat.dto.SendMessageRequest
import com.nhom15.datxebackend.chat.entity.ChatMessage
import com.nhom15.datxebackend.chat.entity.ChatMessageType
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
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/** Regressions for a redispatched ride: preserved threads must stay isolated. */
class ChatRedispatchSecurityTests {
    private val rideRepository: RideRepository = mock(RideRepository::class.java)
    private val conversationRepository: ConversationRepository = mock(ConversationRepository::class.java)
    private val messageRepository: ChatMessageRepository = mock(ChatMessageRepository::class.java)
    private val messageWriter: ChatMessageWriter = mock(ChatMessageWriter::class.java)
    private val rateLimiter: ChatRateLimiter = mock(ChatRateLimiter::class.java)
    private val cloudinaryAssetService: CloudinaryAssetService = mock(CloudinaryAssetService::class.java)
    private val jdbcTemplate: JdbcTemplate = mock(JdbcTemplate::class.java, Answer { invocation ->
        if (invocation.method.name == "query") listOf("Khách hàng") else null
    })
    private val eventPublisher: ApplicationEventPublisher = mock(ApplicationEventPublisher::class.java)
    private val now: Instant = Instant.parse("2026-11-22T09:00:00Z")
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
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private val rideId = 301L
    private val customerId = 11L
    private val oldDriverId = 21L
    private val newDriverId = 22L
    private val oldConversationId = 401L
    private val newConversationId = 402L
    private val customer = AuthUser(customerId, "customer-uid", Role.CUSTOMER, "Khách", null, null, "ACTIVE")
    private val admin = AuthUser(99L, "admin-uid", Role.ADMIN, "Admin", null, null, "ACTIVE")
    private lateinit var ride: Ride
    private lateinit var oldConversation: Conversation
    private lateinit var newConversation: Conversation

    @BeforeEach
    fun setUp() {
        // Fail loudly if another test stranded a matcher instead of masking it behind a retry.
        Mockito.validateMockitoUsage()
        ride = Ride(
            customerId = customerId,
            driverId = newDriverId,
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
        oldConversation = conversation(oldConversationId, oldDriverId)
        newConversation = conversation(newConversationId, newDriverId)
        `when`(rideRepository.findById(rideId)).thenReturn(Optional.of(ride))
        `when`(rideRepository.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride))
        `when`(conversationRepository.findByRideIdAndDriverId(rideId, oldDriverId)).thenReturn(oldConversation)
        `when`(conversationRepository.findByRideIdAndDriverId(rideId, newDriverId)).thenReturn(newConversation)
        `when`(conversationRepository.findAllByRideIdOrderByIdDesc(rideId)).thenReturn(listOf(newConversation))
        `when`(conversationRepository.existsByRideIdAndDriverId(rideId, oldDriverId)).thenReturn(true)
    }

    @Test
    fun `mark read clamps a cross thread id to this conversation newest message`() {
        `when`(messageRepository.findMaxIdInConversation(newConversationId)).thenReturn(410L)
        `when`(messageRepository.markRead(newConversationId, "CUSTOMER", 410L, now)).thenReturn(3)

        val result = service.markRead(rideId, customer, 500L)

        assertEquals(3, result.markedCount)
        verify(messageRepository).markRead(newConversationId, "CUSTOMER", 410L, now)
        verify(eventPublisher).publishEvent(ChatMessagesRead(rideId, 410L, Recipient.Driver(newDriverId)))
    }

    @Test
    fun `mark read does not publish a bogus receipt when the thread max is unknown`() {
        // An unstubbed boxed Long is not a real bound. A client-supplied id may come from
        // another ride, so it must never be acknowledged as this thread's read position.
        `when`(messageRepository.markRead(newConversationId, "CUSTOMER", 500L, now)).thenReturn(2)

        val result = service.markRead(rideId, customer, 500L)

        assertEquals(2, result.markedCount)
        verify(messageRepository).markRead(newConversationId, "CUSTOMER", 500L, now)
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `mark read does not acknowledge an id below this conversation range`() {
        `when`(messageRepository.findMaxIdInConversation(newConversationId)).thenReturn(410L)
        `when`(messageRepository.markRead(newConversationId, "CUSTOMER", 90L, now)).thenReturn(0)

        val result = service.markRead(rideId, customer, 90L)

        assertEquals(0, result.markedCount)
        verify(messageRepository).markRead(newConversationId, "CUSTOMER", 90L, now)
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `admin history merges all preserved conversations of a redispatched ride`() {
        `when`(conversationRepository.findAllByRideIdOrderByIdDesc(rideId))
            .thenReturn(listOf(newConversation, oldConversation))
        val pageable = PageRequest.of(0, 4)
        val cids = listOf(newConversationId, oldConversationId)
        `when`(messageRepository.afterVisible(cids, -1L, "", 100L, pageable))
            .thenReturn(
                listOf(
                    message(101L, oldConversationId, Role.DRIVER),
                    message(105L, newConversationId, Role.DRIVER),
                    message(106L, newConversationId, Role.CUSTOMER),
                ),
            )

        val result = service.adminMessages(rideId, admin, beforeId = null, afterId = 100L, size = 3)

        assertEquals(listOf(101L, 105L, 106L), result.messages.map { it.id })
        assertFalse(result.hasMore)
        verify(messageRepository).afterVisible(cids, -1L, "", 100L, pageable)
    }

    @Test
    fun `previous driver reads his preserved thread and unread count after redispatch`() {
        val previousDriver = AuthUser(oldDriverId, "old-driver-uid", Role.DRIVER, "Tài xế cũ", null, null, "ACTIVE")
        val pageable = PageRequest.of(0, 31)
        `when`(conversationRepository.findAllByRideIdOrderByIdDesc(rideId)).thenReturn(listOf(oldConversation))
        `when`(messageRepository.latestVisible(listOf(oldConversationId), oldConversationId, "", pageable))
            .thenReturn(listOf(message(99L, oldConversationId, Role.DRIVER)))
        `when`(messageRepository.countUnreadFor(oldConversationId, "DRIVER")).thenReturn(2L)

        val page = service.messages(rideId, previousDriver, null, null, 30)

        assertEquals(listOf(99L), page.messages.map { it.id })
        // The former driver is confined to his own thread; the exact argument check is
        // the assertion here (a `never()` on the new thread could never be reached).
        verify(messageRepository).latestVisible(listOf(oldConversationId), oldConversationId, "", pageable)
        assertEquals(2L, service.unreadCount(ride, previousDriver))
    }

    @Test
    fun `previous driver cannot write into a preserved thread after redispatch`() {
        val previousDriver = AuthUser(oldDriverId, "old-driver-uid", Role.DRIVER, "Tài xế cũ", null, null, "ACTIVE")
        val request = SendMessageRequest(UUID.randomUUID(), ChatMessageType.TEXT, "Tin nhắn cũ")

        assertThrows(ForbiddenException::class.java) { service.send(rideId, previousDriver, request) }

        // The old thread stays readable, but no message may be stored in a thread the
        // customer never opens; the rate limiter must not be consumed either.
        verifyNoInteractions(messageWriter, rateLimiter, cloudinaryAssetService)
    }

    @Test
    fun `unrelated driver still cannot read a redispatched ride`() {
        val outsider = AuthUser(23L, "outsider-uid", Role.DRIVER, "Tài xế khác", null, null, "ACTIVE")

        assertThrows(NotFoundException::class.java) { service.messages(rideId, outsider, null, null, 30) }
        verifyNoInteractions(messageRepository)
    }

    @Test
    fun `chat image is scoped to the resolved conversation and sender`() {
        val currentDriver = AuthUser(newDriverId, "driver-uid", Role.DRIVER, "Tài xế", null, null, "ACTIVE")
        val requestClientId = UUID.randomUUID()
        val url = "https://res.cloudinary.com/demo/image/upload/v1/datxe/chat/example.jpg"
        val publicId = "datxe/chat/$newConversationId/DRIVER/$newDriverId/abc123"
        val stored = ChatMessage().apply {
            id = 500L
            conversationId = newConversationId
            senderRole = Role.DRIVER.name
            senderDriverId = newDriverId
            messageType = ChatMessageType.IMAGE.name
            content = ""
            attachmentUrl = url
            cloudinaryPublicId = publicId
            clientMessageId = requestClientId
        }
        `when`(messageRepository.findByConversationIdAndClientMessageId(newConversationId, requestClientId))
            .thenReturn(stored)

        val result = service.send(
            rideId,
            currentDriver,
            SendMessageRequest(
                clientMessageId = requestClientId,
                messageType = ChatMessageType.IMAGE,
                attachmentUrl = url,
                attachmentPublicId = publicId,
            ),
        )

        assertEquals(stored.id, result.message.id)
        verify(cloudinaryAssetService).requireOwnedAsset(
            publicId,
            "datxe/chat/$newConversationId/DRIVER/$newDriverId/",
        )
        verify(cloudinaryAssetService).validateCloudinaryImage(url, publicId)
    }

    @Test
    fun `customer history includes a message left in a preserved thread by a redispatch race`() {
        `when`(conversationRepository.findAllByRideIdOrderByIdDesc(rideId))
            .thenReturn(listOf(newConversation, oldConversation))
        val pageable = PageRequest.of(0, 31)
        `when`(messageRepository.latestVisible(
            listOf(newConversationId, oldConversationId), newConversationId, "", pageable,
        )).thenReturn(
            listOf(
                message(102L, newConversationId, Role.DRIVER),
                message(101L, oldConversationId, Role.CUSTOMER),
            ),
        )

        val page = service.messages(rideId, customer, null, null, 30)

        assertEquals(listOf(102L, 101L), page.messages.map { it.id })
    }

    @Test
    fun `customer mark read covers every preserved thread after a redispatch`() {
        `when`(conversationRepository.findAllByRideIdOrderByIdDesc(rideId))
            .thenReturn(listOf(newConversation, oldConversation))
        `when`(messageRepository.findMaxIdInConversation(newConversationId)).thenReturn(410L)
        `when`(messageRepository.findMaxIdInConversation(oldConversationId)).thenReturn(105L)
        `when`(messageRepository.markRead(newConversationId, "CUSTOMER", 410L, now)).thenReturn(2)
        `when`(messageRepository.markRead(oldConversationId, "CUSTOMER", 105L, now)).thenReturn(1)

        val result = service.markRead(rideId, customer, 500L)

        assertEquals(3, result.markedCount)
        verify(messageRepository).markRead(newConversationId, "CUSTOMER", 410L, now)
        verify(messageRepository).markRead(oldConversationId, "CUSTOMER", 105L, now)
        verify(eventPublisher).publishEvent(ChatMessagesRead(rideId, 410L, Recipient.Driver(newDriverId)))
    }

    @Test
    fun `customer message left in a stale thread notifies the current driver`() {
        val stale = message(103L, oldConversationId, Role.CUSTOMER).apply { content = "Xin chào" }
        `when`(messageRepository.findById(103L)).thenReturn(Optional.of(stale))
        `when`(conversationRepository.findById(oldConversationId)).thenReturn(Optional.of(oldConversation))

        val data = service.loadPushData(103L, rideId)

        assertNotNull(data)
        assertEquals(Recipient.Driver(newDriverId), data!!.recipient)
    }

    @Test
    fun `former driver message in a preserved thread is not pushed to the new driver`() {
        val stale = message(104L, oldConversationId, Role.DRIVER)
        `when`(messageRepository.findById(104L)).thenReturn(Optional.of(stale))
        `when`(conversationRepository.findById(oldConversationId)).thenReturn(Optional.of(oldConversation))

        assertNull(service.loadPushData(104L, rideId))
    }

    private fun conversation(id: Long, driverId: Long) = Conversation().apply {
        this.id = id
        this.rideId = this@ChatRedispatchSecurityTests.rideId
        this.customerId = this@ChatRedispatchSecurityTests.customerId
        this.driverId = driverId
    }

    private fun message(id: Long, conversationId: Long, sender: Role) = ChatMessage().apply {
        this.id = id
        this.conversationId = conversationId
        senderRole = sender.name
        senderCustomerId = customerId.takeIf { sender == Role.CUSTOMER }
        senderDriverId = if (sender == Role.DRIVER) oldDriverId else null
        messageType = ChatMessageType.TEXT.name
        content = "Tin $id"
        clientMessageId = UUID.randomUUID()
    }
}
