package com.nhom15.datxebackend.support

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

@Service
class SupportService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val rides: RideRepository,
    private val assets: CloudinaryAssetService,
    private val events: org.springframework.context.ApplicationEventPublisher? = null,
) {
    @Transactional
    fun create(actor: AuthUser, request: CreateSupportTicket): SupportTicket {
        requireMember(actor)
        if (request.category !in setOf("TRIP", "PAYMENT", "HANDOVER", "ACCOUNT", "OTHER"))
            throw BadRequestException(message = "Loại yêu cầu không hợp lệ")
        if (request.description.trim().length !in 10..4000) throw BadRequestException(message = "Mô tả cần từ 10 đến 4000 ký tự")
        request.rideId?.let { id ->
            val ride = rides.findById(id).orElseThrow { NotFoundException() }
            if (if (actor.role == Role.CUSTOMER) ride.customerId != actor.id else ride.driverId != actor.id) throw ForbiddenException()
        }
        if (request.images.size > 5) throw BadRequestException(message = "Tối đa 5 ảnh")
        request.images.forEach { assets.validateCustomerImage(actor, it.fileUrl, it.publicId) }
        // A retry after a lost response returns the same receipt, even across app restarts.
        val inserted = jdbc.query(
            """INSERT INTO support_tickets(owner_id,owner_role,client_request_id,ride_id,category,description)
               VALUES (?,?,?,?,?,?) ON CONFLICT(owner_id,owner_role,client_request_id) DO NOTHING RETURNING id""",
            { row, _ -> row.getLong("id") }, actor.id, actor.role.name, request.clientRequestId,
            request.rideId, request.category, request.description.trim(),
        ).firstOrNull()
        val id = inserted ?: jdbc.queryForObject(
            "SELECT id FROM support_tickets WHERE owner_id=? AND owner_role=? AND client_request_id=?",
            Long::class.java, actor.id, actor.role.name, request.clientRequestId,
        )!!
        if (inserted != null) {
            request.images.forEach {
                jdbc.update("INSERT INTO support_ticket_images(ticket_id,file_url,public_id) VALUES (?,?,?)", id, it.fileUrl, it.publicId)
            }
            // Tin nhắn đầu tiên của timeline chat chính là mô tả ban đầu của người tạo.
            jdbc.update(
                """INSERT INTO support_ticket_messages(ticket_id,sender_id,sender_role,body,created_at)
                   VALUES (?,?,?,?, (SELECT created_at FROM support_tickets WHERE id=?))""".trimIndent(),
                id, actor.id, actor.role.name, request.description.trim(), id,
            )
        }
        val ticket = detail(actor, id)
        if (ticket.rideId != request.rideId || ticket.category != request.category ||
            ticket.description != request.description.trim() || ticket.images != request.images)
            throw ConflictException(message = "Mã gửi yêu cầu đã được dùng cho nội dung khác. Vui lòng tải lại yêu cầu.")
        return ticket
    }

    @Transactional(readOnly = true)
    fun list(actor: AuthUser, page: Int, status: String? = null): List<SupportTicket> {
        val tickets = listWithoutImages(actor, page, status)
        val images = imagesFor(tickets.map(SupportTicket::id))
        return tickets.map { it.copy(images = images[it.id].orEmpty()) }
    }

    internal fun listWithoutImages(actor: AuthUser, page: Int, status: String?): List<SupportTicket> {
        if (page !in 0..10000) throw BadRequestException(message = "Trang không hợp lệ")
        if (status != null && status !in SUPPORT_STATUSES) throw BadRequestException(message = "Trạng thái yêu cầu không hợp lệ")
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (actor.role != Role.ADMIN) {
            requireMember(actor)
            conditions += "owner_id=? AND owner_role=?"
            params += actor.id
            params += actor.role.name
        }
        if (status != null) { conditions += "status=?"; params += status }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        params += page * 20
        return jdbc.query("SELECT * FROM support_tickets $where ORDER BY id DESC LIMIT 20 OFFSET ?",
            { r, _ -> map(r) }, *params.toTypedArray())
    }

    @Transactional(readOnly = true)
    fun detail(actor: AuthUser, id: Long): SupportTicket {
        val ticket = detailWithoutImages(actor, id)
        return ticket.copy(images = imagesFor(listOf(id))[id].orEmpty())
    }

    internal fun detailWithoutImages(actor: AuthUser, id: Long): SupportTicket {
        val ticket = jdbc.query("SELECT * FROM support_tickets WHERE id=?", { r, _ -> map(r) }, id).firstOrNull() ?: throw NotFoundException()
        if (actor.role != Role.ADMIN && (ticket.ownerId != actor.id || ticket.ownerRole != actor.role.name)) throw NotFoundException()
        return ticket
    }

    @Transactional
    fun update(actor: AuthUser, id: Long, request: UpdateSupportTicket): SupportTicket {
        if (actor.role != Role.ADMIN) throw ForbiddenException()
        if (request.status !in SUPPORT_STATUSES) throw BadRequestException()
        if ((request.response?.length ?: 0) > 4000 || (request.status in setOf("RESOLVED", "CLOSED") && request.response.isNullOrBlank()))
            throw BadRequestException(message = "Cần nội dung trả lời khi đóng yêu cầu; tối đa 4000 ký tự")
        val locked = jdbc.query(
            "SELECT updated_at, status, handled_by, owner_id, owner_role FROM support_tickets WHERE id=? FOR UPDATE",
            { row, _ ->
                arrayOf<Any?>(
                    row.getTimestamp("updated_at").toInstant(),
                    row.getString("status"),
                    row.getObject("handled_by", Long::class.javaObjectType),
                    row.getLong("owner_id"),
                    row.getString("owner_role"),
                )
            },
            id,
        ).firstOrNull() ?: throw NotFoundException()
        val updatedAt = locked[0] as java.time.Instant
        val currentStatus = locked[1] as String?
        val currentHandledBy = locked[2] as Long?
        val ownerId = locked[3] as Long
        val ownerRole = locked[4] as String
        if (currentStatus == "IN_PROGRESS" && currentHandledBy != null && currentHandledBy != actor.id) {
            throw ConflictException(message = "Ticket đang được xử lý bởi admin khác")
        }
        if (request.expectedUpdatedAt != null && request.expectedUpdatedAt != updatedAt)
            throw ConflictException(message = "Yêu cầu đã được người khác cập nhật. Vui lòng tải lại trước khi lưu.")
        val response = request.response?.trim()
        if (jdbc.update("UPDATE support_tickets SET status=?,response=?,handled_by=?,updated_at=GREATEST(clock_timestamp(),updated_at + interval '1 microsecond') WHERE id=?",
                request.status, response, actor.id, id) == 0) throw NotFoundException()
        if (!response.isNullOrEmpty()) {
            // Màn admin cũ vẫn dùng endpoint này: đồng bộ phản hồi vào timeline chat.
            jdbc.update(
                "INSERT INTO support_ticket_messages(ticket_id,sender_id,sender_role,body) VALUES (?,?,?,?)",
                id, actor.id, "ADMIN", response,
            )
            val mappedStatus = when (request.status) {
                "RESOLVED", "CLOSED" -> com.nhom15.datxebackend.support.ticket.TicketStatus.CLOSED
                "IN_PROGRESS" -> com.nhom15.datxebackend.support.ticket.TicketStatus.IN_PROGRESS
                "WAITING_FOR_USER" -> com.nhom15.datxebackend.support.ticket.TicketStatus.WAITING_FOR_USER
                else -> com.nhom15.datxebackend.support.ticket.TicketStatus.OPEN
            }
            events?.publishEvent(
                com.nhom15.datxebackend.support.ticket.TicketReplied(
                    id, "HT-$id", ownerId, Role.valueOf(ownerRole), mappedStatus, response,
                ),
            )
        }
        return detail(actor, id)
    }

    internal fun validateMessageImage(actor: AuthUser, imageUrl: String) = assets.validateOwnedImage(actor, imageUrl)

    private fun imagesFor(ticketIds: List<Long>): Map<Long, List<SupportImage>> {
        if (ticketIds.isEmpty()) return emptyMap()
        val placeholders = ticketIds.joinToString(",") { "?" }
        return jdbc.query(
            """SELECT ticket_id, file_url, public_id FROM support_ticket_images
               WHERE ticket_id IN ($placeholders) ORDER BY ticket_id, id""".trimIndent(),
            { row, _ -> row.getLong("ticket_id") to SupportImage(row.getString("file_url"), row.getString("public_id")) },
            *ticketIds.toTypedArray(),
        ).groupBy({ it.first }, { it.second })
    }

    private fun requireMember(actor: AuthUser) { if (actor.role !in setOf(Role.CUSTOMER, Role.DRIVER)) throw ForbiddenException() }
    private fun map(r: ResultSet): SupportTicket {
        val id = r.getLong("id")
        return SupportTicket(id, "HT-$id", r.getObject("ride_id", Long::class.javaObjectType), r.getString("category"),
            r.getString("description"), r.getString("status"), r.getString("response"),
            emptyList(),
            r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant(), r.getLong("owner_id"), r.getString("owner_role"))
    }
}

private val SUPPORT_STATUSES = setOf("OPEN", "IN_PROGRESS", "WAITING_FOR_USER", "RESOLVED", "CLOSED")
