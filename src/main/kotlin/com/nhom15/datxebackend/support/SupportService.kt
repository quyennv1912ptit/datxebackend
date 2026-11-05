package com.nhom15.datxebackend.support

import com.nhom15.datxebackend.common.enums.Role
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
class SupportService(private val jdbc: JdbcTemplate, private val rides: RideRepository, private val assets: CloudinaryAssetService) {
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
        if (inserted != null) request.images.forEach {
            jdbc.update("INSERT INTO support_ticket_images(ticket_id,file_url,public_id) VALUES (?,?,?)", id, it.fileUrl, it.publicId)
        }
        return detail(actor, id)
    }

    @Transactional(readOnly = true)
    fun list(actor: AuthUser, page: Int, status: String? = null): List<SupportTicket> {
        if (page !in 0..10000) throw BadRequestException(message = "Trang không hợp lệ")
        if (actor.role == Role.ADMIN) {
            if (status != null && status !in setOf("OPEN", "IN_PROGRESS", "RESOLVED")) throw BadRequestException()
            return if (status == null) jdbc.query("SELECT * FROM support_tickets ORDER BY id DESC LIMIT 20 OFFSET ?", { r, _ -> map(r) }, page * 20)
            else jdbc.query("SELECT * FROM support_tickets WHERE status=? ORDER BY id DESC LIMIT 20 OFFSET ?", { r, _ -> map(r) }, status, page * 20)
        }
        requireMember(actor)
        return jdbc.query("SELECT * FROM support_tickets WHERE owner_id=? AND owner_role=? ORDER BY id DESC LIMIT 20 OFFSET ?",
            { r, _ -> map(r) }, actor.id, actor.role.name, page * 20)
    }

    @Transactional(readOnly = true)
    fun detail(actor: AuthUser, id: Long): SupportTicket {
        val ticket = jdbc.query("SELECT * FROM support_tickets WHERE id=?", { r, _ -> map(r) }, id).firstOrNull() ?: throw NotFoundException()
        if (actor.role != Role.ADMIN && (ticket.ownerId != actor.id || ticket.ownerRole != actor.role.name)) throw NotFoundException()
        return ticket
    }

    @Transactional
    fun update(actor: AuthUser, id: Long, request: UpdateSupportTicket): SupportTicket {
        if (actor.role != Role.ADMIN) throw ForbiddenException()
        if (request.status !in setOf("OPEN", "IN_PROGRESS", "RESOLVED")) throw BadRequestException()
        if ((request.response?.length ?: 0) > 4000 || (request.status == "RESOLVED" && request.response.isNullOrBlank()))
            throw BadRequestException(message = "Cần nội dung trả lời khi đóng yêu cầu; tối đa 4000 ký tự")
        if (jdbc.update("UPDATE support_tickets SET status=?,response=?,handled_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                request.status, request.response?.trim(), actor.id, id) == 0) throw NotFoundException()
        return detail(actor, id)
    }

    private fun requireMember(actor: AuthUser) { if (actor.role !in setOf(Role.CUSTOMER, Role.DRIVER)) throw ForbiddenException() }
    private fun map(r: ResultSet): SupportTicket {
        val id = r.getLong("id")
        return SupportTicket(id, "HT-$id", r.getObject("ride_id", Long::class.javaObjectType), r.getString("category"),
            r.getString("description"), r.getString("status"), r.getString("response"),
            jdbc.query("SELECT file_url,public_id FROM support_ticket_images WHERE ticket_id=? ORDER BY id", { image, _ -> SupportImage(image.getString(1), image.getString(2)) }, id),
            r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant(), r.getLong("owner_id"), r.getString("owner_role"))
    }
}
