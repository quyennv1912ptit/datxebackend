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
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Service
class TicketService(
    private val support: SupportService,
    private val tickets: SupportTicketRepository,
    private val messages: TicketMessageRepository,
    private val jdbc: JdbcTemplate,
    private val events: ApplicationEventPublisher,
    /** cloudinary.url dạng cloudinary://key:secret@cloudName; giá trị mặc định chỉ dùng trong unit test. */
    @Value("\${cloudinary.url:}") private val cloudinaryUrl: String = "",
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
        val (body, imageUrl) = validateMessage(actor, request.body, request.imageUrl)
        val senderRole = if (actor.role == Role.DRIVER) TicketSenderRole.DRIVER else TicketSenderRole.CUSTOMER
        val (inserted, message) = writeMessage(
            id, actor.id, senderRole, body, imageUrl, request.clientRequestId, null,
        )
        if (!inserted) return message.toDto()
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
        val (body, imageUrl) = validateMessage(admin, request.body, request.imageUrl)

        // Retry cùng clientRequestId trả về message cũ: không đổi status, không gửi lại push.
        val (inserted, message) = writeMessage(
            id, admin.id, TicketSenderRole.ADMIN, body, imageUrl, request.clientRequestId, status,
        )
        if (!inserted) {
            // Compare with the status recorded by the first write, not the current ticket
            // status: a later message may legitimately have advanced the ticket since then.
            // Legacy rows have no recorded intent and keep the permissive replay behaviour.
            if (message.requestedStatus != null && message.requestedStatus != status) {
                throw ConflictException(message = "Mã gửi tin nhắn đã được dùng với trạng thái khác. Vui lòng tải lại.")
            }
            return message.toDto()
        }
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

    /**
     * Ghi timeline theo khoá idempotency (ticket_id, client_request_id). INSERT ... ON CONFLICT DO NOTHING
     * chạy trong cùng transaction với việc cập nhật ticket; khi bị retry thì trả về row cũ và caller bỏ qua
     * mọi side effect (đổi status, publish FCM).
     */
    private fun writeMessage(
        ticketId: Long,
        senderId: Long,
        senderRole: TicketSenderRole,
        body: String,
        imageUrl: String?,
        clientRequestId: UUID,
        requestedStatus: TicketStatus?,
    ): Pair<Boolean, TicketMessageEntity> {
        val inserted = jdbc.update(
            """INSERT INTO support_ticket_messages
                    (ticket_id, sender_id, sender_role, body, image_url, client_request_id, requested_status, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
               ON CONFLICT (ticket_id, client_request_id) DO NOTHING""".trimIndent(),
            ticketId, senderId, senderRole.name, body, imageUrl, clientRequestId,
            requestedStatus?.name,
        )
        val message = messages.findByTicketIdAndClientRequestId(ticketId, clientRequestId)
            ?: throw ConflictException(message = "Không tìm thấy tin nhắn đã lưu của mã gửi này")
        if (inserted > 0) return true to message
        if (message.senderId != senderId || message.senderRole != senderRole ||
            message.body != body || message.imageUrl != imageUrl
        ) {
            throw ConflictException(message = "Mã gửi tin nhắn đã được dùng cho nội dung khác. Vui lòng tải lại.")
        }
        return false to message
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
            validateOwnedCloudinaryImage(actor, image)
        }
        return text to image
    }

    /**
     * So khớp toàn bộ path: /{cloudName}/image/upload/v{n}/app_dat_xe/{role}/{actorId}/{file}.
     * Không chấp nhận query/fragment, path traversal, cloud khác hoặc thư mục khác của cùng cloud.
     */
    private fun validateOwnedCloudinaryImage(actor: AuthUser, image: String) {
        val uri = runCatching { URI(image) }.getOrNull()
        val cloudName = configuredCloudName()
        if (uri == null || uri.scheme != "https" || uri.host != "res.cloudinary.com" ||
            uri.rawQuery != null || uri.rawFragment != null || cloudName.isNullOrBlank()
        ) {
            throw BadRequestException(message = "Ảnh phải được tải lên Cloudinary bằng API upload của hệ thống")
        }
        val path = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (path.any { it == "." || it == ".." || it.contains('\\') }) {
            throw BadRequestException(message = "Đường dẫn ảnh không hợp lệ")
        }
        val expectedPrefix = listOf(
            cloudName, "image", "upload",
            "app_dat_xe", actor.role.name.lowercase(), actor.id.toString(),
        )
        val version = path.getOrNull(3).orEmpty()
        if (path.size < expectedPrefix.size + 2 ||
            !version.matches(Regex("v\\d+")) ||
            path[0] != expectedPrefix[0] || path[1] != expectedPrefix[1] || path[2] != expectedPrefix[2] ||
            path.drop(4).take(expectedPrefix.size - 3) != expectedPrefix.drop(3)
        ) {
            throw BadRequestException(message = "Ảnh không thuộc tài khoản đang đăng nhập")
        }
    }

    private fun configuredCloudName(): String? {
        val raw = cloudinaryUrl.trim()
        val fromUrl = runCatching { URI(raw).host }.getOrNull()
        return (fromUrl?.takeIf { it.isNotBlank() } ?: raw).takeIf { it.isNotBlank() }
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
