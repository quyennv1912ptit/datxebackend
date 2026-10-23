package com.nhom15.datxebackend.chat.listener

import com.nhom15.datxebackend.chat.dto.ChatReadPayload
import com.nhom15.datxebackend.chat.event.ChatMessageCreated
import com.nhom15.datxebackend.chat.event.ChatMessagesRead
import com.nhom15.datxebackend.chat.service.ChatService
import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.common.websocket.WsEnvelope
import com.nhom15.datxebackend.notification.channel.FcmChannel
import com.nhom15.datxebackend.notification.channel.StompChannel
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class ChatPushListener(
    private val chatService: ChatService,
    private val stompChannel: StompChannel,
    private val fcmChannel: FcmChannel,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("chatTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMessageCreated(event: ChatMessageCreated) {
        val data = chatService.loadPushData(event.messageId, event.rideId) ?: return
        val wsEnvelope = WsEnvelope(PayloadType.CHAT_MESSAGE, data.payload)
        val recipient = data.recipient
        val sentToWebSocket = runCatching { stompChannel.sendIfOnline(recipient, wsEnvelope) }
            .onFailure { log.warn("Không gửi được CHAT_MESSAGE qua WebSocket ride={}", event.rideId, it) }
            .getOrDefault(false)
        if (!sentToWebSocket) {
            runCatching {
                fcmChannel.send(recipient, WsEnvelope(PayloadType.CHAT_MESSAGE, data.fcmPayload))
            }.onFailure { log.warn("Không gửi được chat FCM ride={}", event.rideId, it) }
        }
    }

    @Async("chatTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onMessagesRead(event: ChatMessagesRead) {
        runCatching {
            stompChannel.sendIfOnline(
                event.recipient,
                WsEnvelope(PayloadType.CHAT_READ, ChatReadPayload(event.rideId, event.upToMessageId)),
            )
        }.onFailure { log.warn("Không gửi được CHAT_READ qua WebSocket ride={}", event.rideId, it) }
    }
}
