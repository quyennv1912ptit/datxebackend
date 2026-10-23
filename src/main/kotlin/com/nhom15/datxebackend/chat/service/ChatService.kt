package com.nhom15.datxebackend.chat.service

import com.nhom15.datxebackend.chat.dto.ChatMessageDto
import com.nhom15.datxebackend.chat.dto.ChatMessageFcmPayload
import com.nhom15.datxebackend.chat.dto.ChatMessageFcmMessage
import com.nhom15.datxebackend.chat.dto.ChatMessageCreatedPayload
import com.nhom15.datxebackend.chat.dto.ChatReadPayload
import com.nhom15.datxebackend.chat.dto.MarkReadResponse
import com.nhom15.datxebackend.chat.dto.MessagePage
import com.nhom15.datxebackend.chat.dto.SendMessageRequest
import com.nhom15.datxebackend.chat.entity.ChatMessage
import com.nhom15.datxebackend.chat.entity.ChatMessageType
import com.nhom15.datxebackend.chat.event.ChatMessageCreated
import com.nhom15.datxebackend.chat.event.ChatMessagesRead
import com.nhom15.datxebackend.chat.repository.ChatMessageRepository
import com.nhom15.datxebackend.chat.repository.ChatMessageWriter
import com.nhom15.datxebackend.chat.repository.ConversationRepository
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.Clock

data class ChatSendResult(val message: ChatMessageDto, val duplicated: Boolean)

data class ChatPushData(
    val recipient: Recipient,
    val payload: ChatMessageCreatedPayload,
    val fcmPayload: ChatMessageFcmPayload,
)

@Service
class ChatService(
    private val conversationService: ConversationService,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: ChatMessageRepository,
    private val messageWriter: ChatMessageWriter,
    private val rateLimiter: ChatRateLimiter,
    private val cloudinaryAssetService: CloudinaryAssetService,
    private val rideRepository: RideRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun send(rideId: Long, actor: AuthUser, request: SendMessageRequest): ChatSendResult {
        val context = conversationService.requireParticipant(rideId, actor, lockRide = true)
        val conversation = conversationService.requireOpen(context)
        rateLimiter.check(actor)
        val (content, attachmentUrl, publicId) = validateMessage(rideId, request)

        messageRepository.findByConversationIdAndClientMessageId(conversation.id, request.clientMessageId)?.let {
            return ChatSendResult(ChatMessageDto.from(it), duplicated = true)
        }

        val insertedId = messageWriter.insertIfAbsent(
            conversationId = conversation.id,
            clientMessageId = request.clientMessageId,
            senderRole = actor.role,
            senderId = actor.id,
            messageType = request.messageType.name,
            content = content,
            attachmentUrl = attachmentUrl,
            publicId = publicId,
        )
        val message = if (insertedId != null) {
            messageRepository.findById(insertedId).orElseThrow {
                IllegalStateException("Không đọc lại được tin nhắn vừa lưu")
            }
        } else {
            messageRepository.findByConversationIdAndClientMessageId(conversation.id, request.clientMessageId)
                ?: throw ConflictException(ErrorCode.CONFLICT, "Tin nhắn trùng đang được lưu, vui lòng thử lại")
        }
        val duplicated = insertedId == null
        if (!duplicated) {
            eventPublisher.publishEvent(
                ChatMessageCreated(
                    messageId = message.id,
                    conversationId = conversation.id,
                    rideId = rideId,
                    recipient = conversationService.otherParty(conversation, actor),
                ),
            )
        }
        return ChatSendResult(ChatMessageDto.from(message), duplicated)
    }

    @Transactional(readOnly = true)
    fun messages(
        rideId: Long,
        actor: AuthUser,
        beforeId: Long?,
        afterId: Long?,
        size: Int,
    ): MessagePage {
        val context = conversationService.requireParticipant(rideId, actor)
        val conversation = context.conversation ?: return MessagePage(rideId, emptyList(), false)
        return readPage(rideId, conversation.id, beforeId, afterId, size)
    }

    @Transactional(readOnly = true)
    fun adminMessages(
        rideId: Long,
        actor: AuthUser,
        beforeId: Long?,
        afterId: Long?,
        size: Int,
    ): MessagePage {
        if (actor.role != Role.ADMIN) throw ForbiddenException(message = "Chỉ admin được xem hội thoại")
        if (rideId <= 0) throw BadRequestException(message = "rideId phải lớn hơn 0")
        rideRepository.findById(rideId).orElseThrow { NotFoundException(ErrorCode.RIDE_NOT_FOUND) }
        val conversation = conversationRepository.findByRideId(rideId)
            ?: return MessagePage(rideId, emptyList(), false)
        return readPage(rideId, conversation.id, beforeId, afterId, size)
    }

    @Transactional
    fun markRead(rideId: Long, actor: AuthUser, upToMessageId: Long): MarkReadResponse {
        if (upToMessageId <= 0) throw BadRequestException(message = "upToMessageId phải lớn hơn 0")
        val context = conversationService.requireParticipant(rideId, actor)
        val conversation = context.conversation ?: return MarkReadResponse(rideId, 0)
        val marked = messageRepository.markRead(conversation.id, actor.role.name, upToMessageId, clock.instant())
        if (marked > 0) {
            eventPublisher.publishEvent(
                ChatMessagesRead(
                    rideId = rideId,
                    upToMessageId = upToMessageId,
                    recipient = conversationService.otherParty(conversation, actor),
                ),
            )
        }
        return MarkReadResponse(rideId, marked)
    }

    @Transactional(readOnly = true)
    fun unreadCount(rideId: Long, actor: AuthUser): Long {
        if (actor.role == Role.ADMIN) return 0
        val conversation = conversationRepository.findByRideId(rideId) ?: return 0
        return messageRepository.countUnreadFor(conversation.id, actor.role.name)
    }

    @Transactional(readOnly = true)
    fun loadPushData(messageId: Long, rideId: Long): ChatPushData? {
        val message = messageRepository.findById(messageId).orElse(null) ?: return null
        val conversation = conversationRepository.findById(message.conversationId).orElse(null) ?: return null
        val currentRecipient = when (message.senderRole) {
            Role.CUSTOMER.name -> Recipient.Driver(conversation.driverId)
            Role.DRIVER.name -> Recipient.Customer(conversation.customerId)
            else -> return null
        }
        val senderName = jdbcTemplate.query(
            """SELECT CASE m.sender_role
                      WHEN 'CUSTOMER' THEN COALESCE(c.full_name, 'Khách hàng')
                      WHEN 'DRIVER' THEN COALESCE(d.full_name, 'Tài xế')
                      ELSE 'Người dùng'
                   END AS sender_name
               FROM chat_messages m
               LEFT JOIN customers c ON c.id = m.sender_customer_id
               LEFT JOIN drivers d ON d.id = m.sender_driver_id
               WHERE m.id = ?""".trimIndent(),
            { row, _ -> row.getString("sender_name") },
            messageId,
        ).firstOrNull() ?: return null
        val dto = ChatMessageDto.from(message)
        val preview = if (message.messageType == ChatMessageType.IMAGE.name) {
            "Hình ảnh"
        } else {
            message.content.trim().take(120)
        }
        return ChatPushData(
            recipient = currentRecipient,
            payload = ChatMessageCreatedPayload(rideId, dto, senderName),
            fcmPayload = ChatMessageFcmPayload(
                rideId = rideId,
                message = ChatMessageFcmMessage(
                    id = message.id,
                    senderRole = message.senderRole,
                    messageType = message.messageType,
                    content = preview,
                    createdAt = message.createdAt,
                ),
                senderName = senderName,
            ),
        )
    }

    private fun readPage(
        rideId: Long,
        conversationId: Long,
        beforeId: Long?,
        afterId: Long?,
        size: Int,
    ): MessagePage {
        if (size !in 1..100) throw BadRequestException(message = "size phải nằm trong khoảng 1 đến 100")
        if (beforeId != null && afterId != null) throw BadRequestException(message = "Dùng beforeId hoặc afterId, không dùng đồng thời")
        if (beforeId != null && beforeId <= 0 || afterId != null && afterId <= 0) {
            throw BadRequestException(message = "beforeId và afterId phải lớn hơn 0")
        }
        val pageable = PageRequest.of(0, size + 1)
        val rows = when {
            afterId != null -> messageRepository.after(conversationId, afterId, pageable)
            beforeId != null -> messageRepository.before(conversationId, beforeId, pageable)
            else -> messageRepository.latest(conversationId, pageable)
        }
        return MessagePage(
            rideId = rideId,
            messages = rows.take(size).map(ChatMessageDto::from),
            hasMore = rows.size > size,
        )
    }

    private fun validateMessage(rideId: Long, request: SendMessageRequest): Triple<String, String?, String?> {
        val text = request.content?.trim()?.takeIf(String::isNotEmpty)
        return when (request.messageType) {
            ChatMessageType.TEXT -> {
                if (text == null) throw BadRequestException(message = "Tin nhắn TEXT cần có nội dung")
                if (request.attachmentUrl != null || request.attachmentPublicId != null) {
                    throw BadRequestException(message = "Tin nhắn TEXT không nhận tệp đính kèm")
                }
                Triple(text, null, null)
            }

            ChatMessageType.IMAGE -> {
                val url = request.attachmentUrl?.trim()?.takeIf(String::isNotEmpty)
                    ?: throw BadRequestException(message = "Tin nhắn IMAGE cần attachmentUrl")
                val publicId = request.attachmentPublicId?.trim()?.takeIf(String::isNotEmpty)
                    ?: throw BadRequestException(message = "Tin nhắn IMAGE cần attachmentPublicId")
                cloudinaryAssetService.requireOwnedAsset(publicId, "datxe/chat/$rideId/")
                cloudinaryAssetService.validateCloudinaryImage(url, publicId)
                Triple(text.orEmpty(), url, publicId)
            }
        }
    }
}
