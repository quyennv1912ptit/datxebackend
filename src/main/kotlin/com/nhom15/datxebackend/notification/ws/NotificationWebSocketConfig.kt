package com.nhom15.datxebackend.notification.ws

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
    @Lazy @Qualifier("messageBrokerTaskScheduler")
    private val brokerScheduler: TaskScheduler,
) : WebSocketMessageBrokerConfigurer {

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        val endpoint = registry.addEndpoint("/ws-location")
        // App mobile thường đi qua tunnel/proxy (Cloudflare) làm xuất hiện Origin khác
        // với HTTP API. Xác thực đã nằm ở token trong STOMP CONNECT, không dùng cookie,
        // nên không chặn handshake theo Origin; CORS của HTTP API vẫn giữ nguyên.
        endpoint.setAllowedOriginPatterns("*")
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
