package com.nhom15.datxebackend.notification.ws

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.notification.api.Recipient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message
import org.springframework.messaging.MessagingException
import org.springframework.messaging.SubscribableChannel
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.simp.user.DefaultUserDestinationResolver
import org.springframework.messaging.simp.user.UserDestinationMessageHandler
import org.springframework.messaging.support.ExecutorSubscribableChannel
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.socket.messaging.DefaultSimpUserRegistry
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionSubscribeEvent
import java.nio.charset.StandardCharsets
import java.security.Principal
import java.util.concurrent.CopyOnWriteArrayList

class StompAuthInterceptorSecurityTests {

    private lateinit var interceptor: StompAuthInterceptor
    private lateinit var inboundChannel: ExecutorSubscribableChannel
    private lateinit var brokerChannel: ExecutorSubscribableChannel
    private lateinit var outboundChannel: ExecutorSubscribableChannel
    private lateinit var userRegistry: DefaultSimpUserRegistry
    private lateinit var userDestinationHandler: UserDestinationMessageHandler
    private lateinit var simpleBroker: SimpleBrokerMessageHandler
    private lateinit var messagingTemplate: SimpMessagingTemplate
    private val outboundMessages = CopyOnWriteArrayList<Message<*>>()

    private val principalsByToken = mapOf(
        "tok-customer-a" to Recipient.Customer(1L),
        "tok-customer-b" to Recipient.Customer(2L),
        "tok-driver-10" to Recipient.Driver(10L),
    )

    @BeforeEach
    fun setUp() {
        val verifier = object : AuthTokenVerifier {
            override fun verify(token: String, roleHint: Role?): Recipient? = principalsByToken[token]
        }
        interceptor = StompAuthInterceptor(verifier)

        inboundChannel = ExecutorSubscribableChannel()
        brokerChannel = ExecutorSubscribableChannel()
        outboundChannel = ExecutorSubscribableChannel()
        outboundMessages.clear()
        outboundChannel.subscribe { message -> outboundMessages.add(message) }

        userRegistry = DefaultSimpUserRegistry()
        val resolver = DefaultUserDestinationResolver(userRegistry)
        userDestinationHandler = UserDestinationMessageHandler(inboundChannel, brokerChannel, resolver)

        simpleBroker = SimpleBrokerMessageHandler(inboundChannel, outboundChannel, brokerChannel, listOf("/queue", "/topic"))

        inboundChannel.addInterceptor(interceptor)
        inboundChannel.subscribe(userDestinationHandler)
        brokerChannel.subscribe(userDestinationHandler)

        userDestinationHandler.start()
        simpleBroker.start()

        messagingTemplate = SimpMessagingTemplate(brokerChannel)
        messagingTemplate.userDestinationPrefix = "/user/"
    }

    @AfterEach
    fun tearDown() {
        simpleBroker.stop()
        userDestinationHandler.stop()
    }

    @Test
    fun `principal A cannot spoof PAYMENT_UPDATED, CHAT_MESSAGE, or RIDE_ASSIGNED to principal B`() {
        val principalA = connect("sess-a", "tok-customer-a")
        val principalB = connect("sess-b", "tok-customer-b")
        subscribe("sess-b", principalB, "/user/queue/trip", "sub-b-trip")
        outboundMessages.clear()

        val forgedPayloads = listOf(
            """{"type":"PAYMENT_UPDATED","payload":{"rideId":99,"status":"PAID"}}""",
            """{"type":"CHAT_MESSAGE","payload":{"rideId":99,"content":"forged"}}""",
            """{"type":"RIDE_ASSIGNED","payload":{"rideId":99,"driverId":10}}""",
        )

        for (payload in forgedPayloads) {
            assertThatThrownBy {
                send("sess-a", principalA, "/user/CUSTOMER:2/queue/trip", payload)
            }.isInstanceOf(MessagingException::class.java)
        }

        assertThatThrownBy {
            send("sess-a", principalA, "/user/CUSTOMER:2/queue/rides/99/location", """{"lat":21.0,"lng":105.8}""")
        }.isInstanceOf(MessagingException::class.java)

        val victimMessages = outboundMessages.filter {
            StompHeaderAccessor.wrap(it).sessionId == "sess-b"
        }
        assertThat(victimMessages).isEmpty()
    }

    @Test
    fun `client SEND directly to broker or unsupported destinations is rejected`() {
        val customerA = connect("sess-a", "tok-customer-a")
        val driver10 = connect("sess-d", "tok-driver-10")

        val forbiddenDestinations = listOf(
            "/queue/trip",
            "/queue/trip-usersess-b",
            "/topic/rides/99/location",
            "/user/queue/trip",
            "/user/CUSTOMER:2/queue/trip",
            "/app/unknown",
            "/app/location/extra",
            "/app/chat",
        )

        for (dest in forbiddenDestinations) {
            assertThatThrownBy {
                send("sess-a", customerA, dest, """{"test":true}""")
            }.describedAs("Customer SEND to $dest should be rejected")
                .isInstanceOf(MessagingException::class.java)

            assertThatThrownBy {
                send("sess-d", driver10, dest, """{"test":true}""")
            }.describedAs("Driver SEND to $dest should be rejected")
                .isInstanceOf(MessagingException::class.java)
        }

        assertThatThrownBy {
            val accessor = StompHeaderAccessor.create(StompCommand.SEND)
            accessor.sessionId = "sess-d"
            accessor.user = driver10
            accessor.setLeaveMutable(true)
            interceptor.preSend(MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders), inboundChannel)
        }.isInstanceOf(MessagingException::class.java)
    }

    @Test
    fun `unauthorized SUBSCRIBE destinations are rejected`() {
        val customerA = connect("sess-a", "tok-customer-a")

        val forbiddenSubscribes = listOf(
            "/topic/rides/99/location",
            "/queue/trip",
            "/queue/trip-usersess-b",
            "/user/CUSTOMER:2/queue/trip",
            "/app/location",
        )

        for (dest in forbiddenSubscribes) {
            assertThatThrownBy {
                subscribe("sess-a", customerA, dest, "sub-bad")
            }.describedAs("SUBSCRIBE to $dest should be rejected")
                .isInstanceOf(MessagingException::class.java)
        }
    }

    @Test
    fun `valid application destination enforces role authorization and preserves driver GPS and server notifications`() {
        val customerA = connect("sess-a", "tok-customer-a")
        val customerB = connect("sess-b", "tok-customer-b")
        val driver10 = connect("sess-d", "tok-driver-10")

        // Customer cannot SEND to driver-only /app/location
        assertThatThrownBy {
            send("sess-a", customerA, "/app/location", """{"lat":21.0,"lng":105.8}""")
        }.isInstanceOf(MessagingException::class.java)

        // Authenticated Driver CAN SEND to /app/location
        val driverLocationAccessor = StompHeaderAccessor.create(StompCommand.SEND).apply {
            sessionId = "sess-d"
            user = driver10
            destination = "/app/location"
            setLeaveMutable(true)
        }
        val driverLocationMsg = MessageBuilder.createMessage(
            """{"lat":21.0,"lng":105.8}""".toByteArray(StandardCharsets.UTF_8),
            driverLocationAccessor.messageHeaders,
        )
        val passed = interceptor.preSend(driverLocationMsg, inboundChannel)
        assertThat(passed).isNotNull()

        // Server-side notifications (PAYMENT_UPDATED / CHAT_MESSAGE / GPS location) still deliver to subscribed customer
        subscribe("sess-b", customerB, "/user/queue/trip", "sub-b-trip")
        subscribe("sess-b", customerB, "/user/queue/rides/99/location", "sub-b-loc")
        outboundMessages.clear()

        messagingTemplate.convertAndSendToUser(
            Recipient.Customer(2L).key,
            "/queue/trip",
            mapOf("type" to "CHAT_MESSAGE", "rideId" to 99),
        )
        messagingTemplate.convertAndSendToUser(
            Recipient.Customer(2L).key,
            "/queue/rides/99/location",
            mapOf("lat" to 21.0, "lng" to 105.8),
        )

        val deliveredToB = outboundMessages.filter {
            StompHeaderAccessor.wrap(it).sessionId == "sess-b"
        }
        assertThat(deliveredToB)
            .describedAs("outboundMessages=${outboundMessages.map { StompHeaderAccessor.wrap(it).let { h -> "${h.messageType}:${h.sessionId}:${h.destination}" } }}")
            .hasSize(2)
    }

    private fun connect(sessionId: String, token: String): Principal {
        val accessor = StompHeaderAccessor.create(StompCommand.CONNECT).apply {
            this.sessionId = sessionId
            setNativeHeader("Authorization", "Bearer $token")
            setLeaveMutable(true)
        }
        val message = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
        inboundChannel.send(message)
        val principal = accessor.user!!
        userRegistry.onApplicationEvent(SessionConnectedEvent(this, message, principal))
        return principal
    }

    private fun subscribe(sessionId: String, principal: Principal, destination: String, subId: String) {
        val accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE).apply {
            this.sessionId = sessionId
            this.user = principal
            this.destination = destination
            this.subscriptionId = subId
            setLeaveMutable(true)
        }
        val message = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
        inboundChannel.send(message)
        userRegistry.onApplicationEvent(SessionSubscribeEvent(this, message, principal))
    }

    private fun send(sessionId: String, principal: Principal, destination: String, payload: String) {
        val accessor = StompHeaderAccessor.create(StompCommand.SEND).apply {
            this.sessionId = sessionId
            this.user = principal
            this.destination = destination
            setLeaveMutable(true)
        }
        val message = MessageBuilder.createMessage(
            payload.toByteArray(StandardCharsets.UTF_8),
            accessor.messageHeaders,
        )
        inboundChannel.send(message)
    }
}
