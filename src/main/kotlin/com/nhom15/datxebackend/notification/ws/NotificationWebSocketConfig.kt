package com.nhom15.datxebackend.notification.ws

import com.nhom15.datxebackend.config.properties.CorsProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.scheduling.TaskScheduler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration

@Configuration
@EnableWebSocketMessageBroker
class NotificationWebSocketConfig(
    private val stompAuthInterceptor: StompAuthInterceptor,
    private val corsProperties: CorsProperties,
    @Lazy @Qualifier("messageBrokerTaskScheduler")
    private val brokerScheduler: TaskScheduler,
) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val endpoint = registry.addEndpoint("/ws-location")
        if (corsProperties.allowedOrigins.isNotEmpty()) {
            endpoint.setAllowedOriginPatterns(*corsProperties.allowedOrigins.toTypedArray())
        }
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/queue", "/topic")
            .setHeartbeatValue(longArrayOf(10_000, 10_000))
            .setTaskScheduler(brokerScheduler)
        registry.setApplicationDestinationPrefixes("/app")   // client gửi: /app/location
        registry.setUserDestinationPrefix("/user")           // client nghe: /user/queue/trip, /user/queue/rides/{id}/location
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(stompAuthInterceptor)
    }

    override fun configureWebSocketTransport(registry: WebSocketTransportRegistration) {
        registry.setMessageSizeLimit(64 * 1024)
            .setSendBufferSizeLimit(512 * 1024)
            .setSendTimeLimit(20_000)
    }
}
