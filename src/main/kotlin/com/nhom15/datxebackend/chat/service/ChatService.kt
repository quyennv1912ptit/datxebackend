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
import com.nhom15.datxebackend.chat.entity.Conversation
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
import com.nhom15.datxebackend.ride.entity.Ride
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
        // V17 preserves one thread per driver. A redispatched driver keeps read access to
        // his own history, but the customer only reads the current assignment's thread:
        // letting the former driver write there creates messages nobody will ever see.
        if (actor.role == Role.DRIVER && context.ride.driverId != conversation.driverId) {
            throw ForbiddenException(message = "Cuộc hội thoại đã kết thúc khi chuyến xe được giao lại")
        }
        val (content, attachmentUrl, publicId) = validateMessage(conversation, actor, request)

        messageRepository.findByConversationIdAndClientMessageId(conversation.id, request.clientMessageId)?.let {
            requireSameMessage(it, actor, request, content, attachmentUrl, publicId)
            return ChatSendResult(ChatMessageDto.from(it), duplicated = true)
        }

        // Thứ tự quan trọng: validate nội dung/trạng thái và tra cứu idempotency xong mới
        // tiêu quota. Client retry đúng clientMessageId sau khi mất response phải nhận lại
        // tin cũ dù bucket đã cạn, nếu check ở trên sẽ trả 429 thay vì kết quả cũ.
        rateLimiter.check(actor)

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
            val stored = messageRepository.findByConversationIdAndClientMessageId(conversation.id, request.clientMessageId)
                ?: throw ConflictException(ErrorCode.CONFLICT, "Tin nhắn trùng đang được lưu, vui lòng thử lại")
            requireSameMessage(stored, actor, request, content, attachmentUrl, publicId)
            stored
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
        val ownConversation = context.conversation ?: return MessagePage(rideId, emptyList(), false)
        // V17 keeps one thread per driver. A message a customer sent just before a redispatch
        // can land in the previous thread, so the customer must see the ride-wide timeline;
        // the current driver sees its customer messages without other drivers' replies. A
        // former driver is confined to his own preserved thread and has no cross-thread view.
        val threads = conversationRepository.findAllByRideIdOrderByIdDesc(rideId)
            .takeIf { it.isNotEmpty() }
            ?: return MessagePage(rideId, emptyList(), false)
        val (conversationIds, me) = when {
            actor.role == Role.DRIVER && context.ride.driverId != ownConversation.driverId ->
                listOf(ownConversation.id) to ""
            actor.role == Role.DRIVER -> threads.map(Conversation::id) to Role.DRIVER.name
            else -> threads.map(Conversation::id) to ""
        }
        return readPage(rideId, conversationIds, ownConversation.id, me, beforeId, afterId, size)
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
        // V17 preserves a thread per driver on a redispatched ride. Merge all of
        // them globally by message id so admin never loses the old driver's page.
        val conversationIds = conversationRepository.findAllByRideIdOrderByIdDesc(rideId)
            .takeIf { it.isNotEmpty() }
            ?.map(Conversation::id)
            ?: return MessagePage(rideId, emptyList(), false)
        // Admin sees every thread; ownCid is irrelevant because me = "" disables the filter.
        return readPage(rideId, conversationIds, -1L, "", beforeId, afterId, size)
    }

    @Transactional
    fun markRead(rideId: Long, actor: AuthUser, upToMessageId: Long): MarkReadResponse {
        if (upToMessageId <= 0) throw BadRequestException(message = "upToMessageId phải lớn hơn 0")
        val context = conversationService.requireParticipant(rideId, actor)
        val conversation = context.conversation ?: return MarkReadResponse(rideId, 0)
        // The customer reads the ride-wide timeline, so his receipt must cover every
        // preserved thread. Drivers keep receipts scoped to their own thread.
        val conversationIds = if (actor.role == Role.CUSTOMER) {
            conversationRepository.findAllByRideIdOrderByIdDesc(rideId).map(Conversation::id)
        } else {
            listOf(conversation.id)
        }
        var marked = 0
        var highestAcknowledged = 0L
        conversationIds.forEach { conversationId ->
            // A client may hold an id from a different ride/thread. Never let it become
            // a read receipt for this thread: bound it by the thread's own newest id.
            // Treat a non-positive max (e.g. Mockito's 0L default) as unknown.
            val maxIdInConversation = messageRepository.findMaxIdInConversation(conversationId)
                ?.takeIf { it > 0 }
            val effectiveUpTo = maxIdInConversation?.let { minOf(upToMessageId, it) } ?: upToMessageId
            marked += messageRepository.markRead(conversationId, actor.role.name, effectiveUpTo, clock.instant())
            // Without a real thread max there is no trustworthy id to acknowledge: a
            // client-supplied id may come from another ride. Never publish it as a receipt.
            if (maxIdInConversation != null) {
                highestAcknowledged = maxOf(highestAcknowledged, effectiveUpTo)
            }
        }
        if (marked > 0 && highestAcknowledged > 0) {
            eventPublisher.publishEvent(
                ChatMessagesRead(
                    rideId = rideId,
                    upToMessageId = highestAcknowledged,
                    recipient = conversationService.otherParty(conversation, actor),
                ),
            )
        }
        return MarkReadResponse(rideId, marked)
    }

    @Transactional(readOnly = true)
    fun unreadCount(ride: Ride, actor: AuthUser): Long {
        if (actor.role == Role.ADMIN) return 0
        val conversation = conversationService.forActor(ride, actor) ?: return 0
        if (actor.role != Role.CUSTOMER) {
            return messageRepository.countUnreadFor(conversation.id, actor.role.name)
        }
        // Customer history is ride-wide after a redispatch; count every preserved thread.
        return conversationRepository.findAllByRideIdOrderByIdDesc(ride.id)
            .sumOf { messageRepository.countUnreadFor(it.id, actor.role.name) }
    }

    @Transactional(readOnly = true)
    fun loadPushData(messageId: Long, rideId: Long): ChatPushData? {
        val message = messageRepository.findById(messageId).orElse(null) ?: return null
        val conversation = conversationRepository.findById(message.conversationId).orElse(null) ?: return null
        val ride = rideRepository.findById(rideId).orElse(null) ?: return null
        if (conversation.rideId != rideId) return null
        val currentRecipient = when (message.senderRole) {
            // A customer's message can land in the previous thread if a redispatch committed
            // just after send(); notify the driver actually assigned now.
            Role.CUSTOMER.name -> ride.driverId?.let { Recipient.Driver(it) } ?: return null
            // A former driver's preserved-thread message must never reach the new assignment.
            Role.DRIVER.name -> if (ride.driverId == conversation.driverId) {
                Recipient.Customer(conversation.customerId)
            } else {
                return null
            }
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
        conversationIds: Collection<Long>,
        ownConversationId: Long,
        me: String,
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
        // Visibility is applied by the query BEFORE paging. Filtering after a per-thread
        // PageRequest would drop excluded rows first and make hasMore false / hide older
        // messages that sit just beyond the truncated page (F1). Chat message ids are
        // globally increasing, so one ride-wide query yields a correct merged page.
        val rows = when {
            afterId != null -> messageRepository.afterVisible(
                conversationIds, ownConversationId, me, afterId, pageable,
            )
            beforeId != null -> messageRepository.beforeVisible(
                conversationIds, ownConversationId, me, beforeId, pageable,
            )
            else -> messageRepository.latestVisible(conversationIds, ownConversationId, me, pageable)
        }
        val ordered = if (afterId != null) rows.sortedBy(ChatMessage::id) else rows.sortedByDescending(ChatMessage::id)
        return MessagePage(
            rideId = rideId,
            messages = ordered.take(size).map(ChatMessageDto::from),
            hasMore = rows.size > size,
        )
    }

    /**
     * clientMessageId do client tạo ra cũng nằm trong ChatMessageDto, nên participant khác
     * có thể nhìn thấy và dùng lại UUID đó với nội dung khác. Idempotency chỉ được trả về
     * "duplicate" khi cùng một người gửi lại đúng payload cũ; nếu khác thì báo conflict
     * thay vì nuốt tin mới im lặng (không insert, không publish event/push).
     */
    private fun requireSameMessage(
        stored: ChatMessage,
        actor: AuthUser,
        request: SendMessageRequest,
        content: String,
        attachmentUrl: String?,
        publicId: String?,
    ) {
        val sameSender = when (actor.role) {
            Role.CUSTOMER -> stored.senderRole == Role.CUSTOMER.name && stored.senderCustomerId == actor.id
            Role.DRIVER -> stored.senderRole == Role.DRIVER.name && stored.senderDriverId == actor.id
            else -> false
        }
        val samePayload = stored.messageType == request.messageType.name &&
            stored.content == content &&
            stored.attachmentUrl == attachmentUrl &&
            stored.cloudinaryPublicId == publicId
        if (!sameSender || !samePayload) {
            throw ConflictException(ErrorCode.CONFLICT, "Mã tin nhắn đã được dùng cho nội dung hoặc người gửi khác")
        }
    }

    private fun validateMessage(
        conversation: Conversation,
        actor: AuthUser,
        request: SendMessageRequest,
    ): Triple<String, String?, String?> {
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
                // Uploads must be scoped to the thread and the sender. A ride-scoped
                // folder alone lets another driver on a redispatched ride attach
                // his image to this thread (V17 keeps multiple threads per ride).
                cloudinaryAssetService.requireOwnedAsset(
                    publicId,
                    "datxe/chat/${conversation.id}/${actor.role.name}/${actor.id}/",
                )
                cloudinaryAssetService.validateCloudinaryImage(url, publicId)
                Triple(text.orEmpty(), url, publicId)
            }
        }
    }
}
