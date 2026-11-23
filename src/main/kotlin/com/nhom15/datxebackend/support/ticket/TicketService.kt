package com.nhom15.datxebackend.support.ticket

import com.nhom15.datxebackend.admin.dto.AdminSearchFilter
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.support.CreateSupportTicket
import com.nhom15.datxebackend.support.SupportImage
import com.nhom15.datxebackend.support.SupportService
import com.nhom15.datxebackend.support.SupportTicket
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant

@Service
class TicketService(
    private val support: SupportService,
    private val tickets: SupportTicketRepository,
    private val messages: TicketMessageRepository,
    private val jdbc: JdbcTemplate,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun create(actor: AuthUser, request: CreateTicketRequest): TicketSummary {
        // Dùng lại validation/idempotency của module support cũ; create() cũng seed tin nhắn đầu tiên.
        val ticket = support.create(
            actor,
            CreateSupportTicket(
                clientRequestId = request.clientRequestId,
                rideId = request.rideId,
                category = request.category,
                description = request.description,
            ),
        )
        return ticket.toSummary()
    }

    @Transactional(readOnly = true)
    fun list(actor: AuthUser, page: Int, status: TicketStatus?): List<TicketSummary> =
        support.listWithoutImages(actor, page, status?.name).map { it.toSummary() }

    @Transactional(readOnly = true)
    fun detail(actor: AuthUser, id: Long): TicketDetail {
        val ticket = support.detailWithoutImages(actor, id)
        val latest = messageDtos(id, includePrevious = true)
        return TicketDetail(ticket.toSummary(), latest.takeLast(MESSAGE_PAGE_SIZE), latest.size > MESSAGE_PAGE_SIZE)
    }

    @Transactional(readOnly = true)
    fun messages(
        actor: AuthUser, id: Long, beforeId: Long? = null, afterId: Long? = null, size: Int = MESSAGE_PAGE_SIZE,
    ): List<TicketMessageDto> {
        support.detailWithoutImages(actor, id) // kiểm tra quyền sở hữu
        return messageDtos(id, beforeId, afterId, size)
    }

    /** Khách/tài xế trả lời: khóa hàng FOR UPDATE, chặn ticket CLOSED, đưa về OPEN và giải phóng admin đang xử lý. */
    @Transactional
    fun sendUserMessage(actor: AuthUser, id: Long, request: SendTicketMessageRequest): TicketMessageDto {
        val locked = tickets.findForUpdate(id) ?: throw NotFoundException()
        if (actor.role != Role.ADMIN && (locked.ownerId != actor.id || locked.ownerRole != actor.role)) {
            throw NotFoundException()
        }
        val senderRole = if (actor.role == Role.DRIVER) TicketSenderRole.DRIVER else TicketSenderRole.CUSTOMER
        retryMessage(actor, id, senderRole, request, null)?.let { return it }
        if (locked.status == TicketStatus.CLOSED) {
            throw ConflictException(message = "Yêu cầu hỗ trợ đã đóng, không thể gửi thêm tin nhắn")
        }
        val (body, imageUrl) = validateMessage(actor, request.body, request.imageUrl)
        val message = messages.save(TicketMessageEntity(
            id, actor.id, senderRole, body, imageUrl, clientMessageId = request.clientMessageId,
        ))
        jdbc.update(
            """UPDATE support_tickets
               SET status = 'OPEN', handled_by = NULL,
                   updated_at = GREATEST(clock_timestamp(), updated_at + interval '1 microsecond')
               WHERE id = ?""".trimIndent(),
            id,
        )
        return message.toDto()
    }

    /**
     * Admin mở ticket: claim sang IN_PROGRESS. Admin khác đang xử lý thì trả 409
     * để không hai người cùng trả lời một ticket.
     */
    @Transactional
    fun claim(admin: AuthUser, id: Long): AdminTicketDetail {
        val ticket = tickets.findForUpdate(id) ?: throw NotFoundException()
        if (ticket.status == TicketStatus.IN_PROGRESS && ticket.handledBy != null && ticket.handledBy != admin.id) {
            throw ConflictException(message = "Ticket đang được xử lý bởi admin khác")
        }
        if (ticket.status == TicketStatus.OPEN || ticket.status == TicketStatus.WAITING_FOR_USER ||
            (ticket.status == TicketStatus.IN_PROGRESS && ticket.handledBy == null)
        ) {
            ticket.status = TicketStatus.IN_PROGRESS
            ticket.handledBy = admin.id
            ticket.updatedAt = Instant.now()
            tickets.saveAndFlush(ticket)
        }
        return adminDetail(admin, id)
    }

    /** Admin trả lời: lưu message, cập nhật status/updated_at, phát event FCM sau commit. */
    @Transactional
    fun sendAdminMessage(admin: AuthUser, id: Long, request: SendTicketMessageRequest): TicketMessageDto {
        val ticket = tickets.findForUpdate(id) ?: throw NotFoundException()
        val status = request.status ?: TicketStatus.WAITING_FOR_USER
        retryMessage(admin, id, TicketSenderRole.ADMIN, request, status)?.let { return it }
        if (ticket.status == TicketStatus.IN_PROGRESS && ticket.handledBy != null && ticket.handledBy != admin.id) {
            throw ConflictException(message = "Ticket đang được xử lý bởi admin khác")
        }
        if (status !in ADMIN_STATUSES) throw BadRequestException(message = "Trạng thái ticket không hợp lệ")
        val (body, imageUrl) = validateMessage(admin, request.body, request.imageUrl)

        val message = messages.save(TicketMessageEntity(
            id, admin.id, TicketSenderRole.ADMIN, body, imageUrl,
            clientMessageId = request.clientMessageId, requestedStatus = status,
        ))
        ticket.status = status
        ticket.handledBy = admin.id
        ticket.response = body // giữ cột response cũ cho màn admin/app cũ
        ticket.updatedAt = Instant.now()
        tickets.saveAndFlush(ticket)
        events.publishEvent(TicketReplied(id, ticket.code(), ticket.ownerId, ticket.ownerRole, status, body))
        return message.toDto()
    }

    @Transactional(readOnly = true)
    fun adminList(status: TicketStatus?, page: Int, size: Int, filter: AdminSearchFilter = AdminSearchFilter(), ownerRole: String? = null, category: String? = null): PageResponse<AdminTicketSummary> {
        if (page < 0) throw BadRequestException(message = "page phải lớn hơn hoặc bằng 0")
        if (size !in 1..100) throw BadRequestException(message = "size phải nằm trong khoảng 1 đến 100")
        filter.validate()
        if (ownerRole != null && ownerRole !in setOf("CUSTOMER", "DRIVER")) throw BadRequestException(message = "Vai trò không hợp lệ")
        if (category != null && category !in setOf("TRIP", "PAYMENT", "HANDOVER", "ACCOUNT", "OTHER")) throw BadRequestException(message = "Chủ đề không hợp lệ")
        val predicates = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (status != null) { predicates += "t.status = ?"; params += status.name }
        ownerRole?.let { predicates += "t.owner_role = ?"; params += it }
        category?.let { predicates += "t.category = ?"; params += it }
        filter.createdFrom?.let { predicates += "t.created_at >= ?"; params += java.sql.Timestamp.from(it) }
        filter.createdTo?.let { predicates += "t.created_at < ?"; params += java.sql.Timestamp.from(it) }
        filter.q?.trim()?.takeIf(String::isNotEmpty)?.let {
            predicates += "(LOWER(COALESCE(c.full_name, d.full_name)) LIKE ? ESCAPE '!' OR COALESCE(c.phone_number, d.phone_number) LIKE ? ESCAPE '!' OR LOWER('HT-' || t.id::text) LIKE ? ESCAPE '!' OR t.ride_id::text = ?)"
            params.addAll(listOf(filter.pattern(), filter.pattern(), filter.pattern(), it.removePrefix("#")))
        }
        val where = if (predicates.isEmpty()) "" else "WHERE " + predicates.joinToString(" AND ")
        val total = jdbc.queryForObject(
            """SELECT COUNT(*) FROM support_tickets t
               LEFT JOIN customers c ON t.owner_role = 'CUSTOMER' AND c.id = t.owner_id
               LEFT JOIN drivers d ON t.owner_role = 'DRIVER' AND d.id = t.owner_id $where""",
            Long::class.java,
            *params.toTypedArray(),
        ) ?: 0L
        val content = jdbc.query(
            """SELECT t.id, t.owner_id, t.owner_role, t.category, t.ride_id, t.status,
                      t.handled_by, t.created_at, t.updated_at,
                      COALESCE(c.full_name, d.full_name) AS owner_name,
                      COALESCE(c.phone_number, d.phone_number) AS owner_phone
               FROM support_tickets t
               LEFT JOIN customers c ON t.owner_role = 'CUSTOMER' AND c.id = t.owner_id
               LEFT JOIN drivers d ON t.owner_role = 'DRIVER' AND d.id = t.owner_id
               $where
               ORDER BY t.updated_at DESC, t.id DESC
               LIMIT ? OFFSET ?""".trimIndent(),
            { row, _ -> row.toAdminSummary() },
            *(params + listOf<Any>(size, page.toLong() * size)).toTypedArray(),
        )
        val totalPages = if (total == 0L) 0 else ((total + size - 1) / size).toInt()
        return PageResponse(content, page, size, total, totalPages, page + 1 < totalPages)
    }

    @Transactional(readOnly = true)
    fun adminDetail(admin: AuthUser, id: Long): AdminTicketDetail {
        val ticket = support.detail(admin, id)
        val summary = adminSummary(id) ?: throw NotFoundException()
        val latest = messageDtos(id, includePrevious = true)
        return AdminTicketDetail(
            summary, ticket.description, ticket.images, latest.takeLast(MESSAGE_PAGE_SIZE), latest.size > MESSAGE_PAGE_SIZE,
        )
    }

    private fun adminSummary(id: Long): AdminTicketSummary? = jdbc.query(
        """SELECT t.id, t.owner_id, t.owner_role, t.category, t.ride_id, t.status,
                  t.handled_by, t.created_at, t.updated_at,
                  COALESCE(c.full_name, d.full_name) AS owner_name,
                  COALESCE(c.phone_number, d.phone_number) AS owner_phone
           FROM support_tickets t
           LEFT JOIN customers c ON t.owner_role = 'CUSTOMER' AND c.id = t.owner_id
           LEFT JOIN drivers d ON t.owner_role = 'DRIVER' AND d.id = t.owner_id
           WHERE t.id = ?""".trimIndent(),
        { row, _ -> row.toAdminSummary() },
        id,
    ).firstOrNull()

    private fun messageDtos(
        ticketId: Long, beforeId: Long? = null, afterId: Long? = null, size: Int = MESSAGE_PAGE_SIZE,
        includePrevious: Boolean = false,
    ): List<TicketMessageDto> {
        if (size !in 1..MESSAGE_PAGE_SIZE) throw BadRequestException(message = "size phải nằm trong khoảng 1 đến 100")
        if (beforeId != null && afterId != null) throw BadRequestException(message = "Chỉ dùng một trong beforeId và afterId")
        val cursor = beforeId ?: afterId
        if (cursor != null && cursor <= 0) throw BadRequestException(message = "Mã tin nhắn phải lớn hơn 0")

        val ascending = afterId != null
        val direction = if (ascending) "ASC" else "DESC"
        val parameters = mutableListOf<Any>(ticketId)
        val boundary = if (cursor == null) "" else {
            val operator = if (ascending) ">" else "<"
            parameters.addAll(listOf(ticketId, cursor))
            "AND (created_at, id) $operator (SELECT created_at, id FROM support_ticket_messages WHERE ticket_id = ? AND id = ?)"
        }
        parameters.add(if (includePrevious) size + 1 else size)
        val page = jdbc.query(
            """SELECT id, ticket_id, sender_id, sender_role, body, image_url, created_at
               FROM support_ticket_messages WHERE ticket_id = ? $boundary
               ORDER BY created_at $direction, id $direction LIMIT ?""".trimIndent(),
            { row, _ ->
                TicketMessageDto(
                    id = row.getLong("id"),
                    ticketId = row.getLong("ticket_id"),
                    senderId = row.getLong("sender_id"),
                    senderRole = TicketSenderRole.valueOf(row.getString("sender_role")),
                    body = row.getString("body"),
                    imageUrl = row.getString("image_url"),
                    createdAt = row.getTimestamp("created_at").toInstant(),
                )
            },
            *parameters.toTypedArray(),
        )
        return if (ascending) page else page.asReversed()
    }

    /** The caller already holds the ticket row lock for the whole lookup and write. */
    private fun retryMessage(
        actor: AuthUser, ticketId: Long, senderRole: TicketSenderRole,
        request: SendTicketMessageRequest, status: TicketStatus?,
    ): TicketMessageDto? {
        val key = request.clientMessageId ?: return null
        val prior = messages.findByTicketIdAndSenderIdAndSenderRoleAndClientMessageId(ticketId, actor.id, senderRole, key)
            ?: return null
        val image = request.imageUrl?.trim()?.takeIf(String::isNotEmpty)
        if (prior.body != request.body.trim() || prior.imageUrl != image || prior.requestedStatus != status) {
            throw ConflictException(message = "Mã tin nhắn đã được dùng cho nội dung khác")
        }
        return prior.toDto()
    }

    /** Một tin nhắn hợp lệ phải có text hoặc ảnh; nếu có ảnh, ảnh phải thuộc thư mục Cloudinary của chính người gửi. */
    private fun validateMessage(actor: AuthUser, body: String, imageUrl: String?): Pair<String, String?> {
        val text = body.trim()
        val image = imageUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (text.isEmpty() && image == null) {
            throw BadRequestException(message = "Tin nhắn phải có nội dung hoặc ảnh")
        }
        if (text.length > 4_000) throw BadRequestException(message = "Nội dung tin nhắn tối đa 4000 ký tự")
        if (image != null) {
            if (image.length > 2_048) throw BadRequestException(message = "Đường dẫn ảnh quá dài")
            support.validateMessageImage(actor, image)
        }
        return text to image
    }

    private fun SupportTicket.toSummary() = TicketSummary(
        id = id,
        code = code,
        rideId = rideId,
        category = category,
        description = description,
        status = TicketStatus.valueOf(status),
        createdAt = createdAt,
        updatedAt = updatedAt,
        ownerId = ownerId,
        ownerRole = ownerRole,
    )

    private fun TicketMessageEntity.toDto() = TicketMessageDto(
        id = id,
        ticketId = ticketId,
        senderId = senderId,
        senderRole = senderRole,
        body = body,
        imageUrl = imageUrl,
        createdAt = createdAt,
    )

    private fun ResultSet.toAdminSummary() = AdminTicketSummary(
        id = getLong("id"),
        code = "HT-${getLong("id")}",
        ownerId = getLong("owner_id"),
        ownerName = getString("owner_name"),
        ownerPhoneNumber = getString("owner_phone"),
        ownerRole = getString("owner_role"),
        category = getString("category"),
        rideId = getObject("ride_id", Long::class.javaObjectType),
        status = TicketStatus.valueOf(getString("status")),
        createdAt = getTimestamp("created_at").toInstant(),
        updatedAt = getTimestamp("updated_at").toInstant(),
        handledBy = getObject("handled_by", Long::class.javaObjectType),
    )

    companion object {
        const val MESSAGE_PAGE_SIZE = 100

        private val ADMIN_STATUSES = setOf(
            TicketStatus.OPEN,
            TicketStatus.IN_PROGRESS,
            TicketStatus.WAITING_FOR_USER,
            TicketStatus.RESOLVED,
            TicketStatus.CLOSED,
        )
    }
}
