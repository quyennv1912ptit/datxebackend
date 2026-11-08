package com.nhom15.datxebackend.support.ticket

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
        support.list(actor, page, status?.name).map { it.toSummary() }

    @Transactional(readOnly = true)
    fun detail(actor: AuthUser, id: Long): TicketDetail {
        val ticket = support.detail(actor, id)
        return TicketDetail(ticket.toSummary(), messageDtos(id))
    }

    @Transactional(readOnly = true)
    fun messages(actor: AuthUser, id: Long): List<TicketMessageDto> {
        support.detail(actor, id) // kiểm tra quyền sở hữu
        return messageDtos(id)
    }

    /** Khách/tài xế trả lời: đưa ticket về OPEN và giải phóng admin đang xử lý. */
    @Transactional
    fun sendUserMessage(actor: AuthUser, id: Long, request: SendTicketMessageRequest): TicketMessageDto {
        support.detail(actor, id)
        val body = validateBody(request.body)
        val senderRole = if (actor.role == Role.DRIVER) TicketSenderRole.DRIVER else TicketSenderRole.CUSTOMER
        val message = messages.save(TicketMessageEntity(id, actor.id, senderRole, body))
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
        if (ticket.status == TicketStatus.IN_PROGRESS && ticket.handledBy != null && ticket.handledBy != admin.id) {
            throw ConflictException(message = "Ticket đang được xử lý bởi admin khác")
        }
        val status = request.status ?: TicketStatus.WAITING_FOR_USER
        if (status !in ADMIN_STATUSES) throw BadRequestException(message = "Trạng thái ticket không hợp lệ")
        val body = validateBody(request.body)

        val message = messages.save(TicketMessageEntity(id, admin.id, TicketSenderRole.ADMIN, body))
        ticket.status = status
        ticket.handledBy = admin.id
        ticket.response = body // giữ cột response cũ cho màn admin/app cũ
        ticket.updatedAt = Instant.now()
        tickets.saveAndFlush(ticket)
        events.publishEvent(TicketReplied(id, ticket.code(), ticket.ownerId, ticket.ownerRole, status, body))
        return message.toDto()
    }

    @Transactional(readOnly = true)
    fun adminList(status: TicketStatus?, page: Int, size: Int): PageResponse<AdminTicketSummary> {
        if (page < 0) throw BadRequestException(message = "page phải lớn hơn hoặc bằng 0")
        if (size !in 1..100) throw BadRequestException(message = "size phải nằm trong khoảng 1 đến 100")
        val where = if (status == null) "" else "WHERE t.status = ?"
        val params = mutableListOf<Any>()
        if (status != null) params += status.name
        val total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM support_tickets t $where",
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
        return AdminTicketDetail(summary, ticket.description, ticket.images, messageDtos(id))
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

    private fun messageDtos(ticketId: Long): List<TicketMessageDto> =
        messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId).map { it.toDto() }

    private fun validateBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed.length > 4_000) {
            throw BadRequestException(message = "Nội dung tin nhắn phải từ 1 đến 4000 ký tự")
        }
        return trimmed
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

    private companion object {
        val ADMIN_STATUSES = setOf(
            TicketStatus.OPEN,
            TicketStatus.IN_PROGRESS,
            TicketStatus.WAITING_FOR_USER,
            TicketStatus.RESOLVED,
            TicketStatus.CLOSED,
        )
    }
}
