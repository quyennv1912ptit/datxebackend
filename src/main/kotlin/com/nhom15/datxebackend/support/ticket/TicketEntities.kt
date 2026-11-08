package com.nhom15.datxebackend.support.ticket

import com.nhom15.datxebackend.common.enums.Role
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Ánh xạ bảng support_tickets hiện hữu. Các endpoint cũ vẫn ghi bằng JdbcTemplate;
 * entity này phục vụ luồng chat/claim mới để không phải viết lại toàn bộ module cũ.
 */
@Entity
@Table(name = "support_tickets")
class SupportTicketEntity(
    @Column(name = "owner_id", nullable = false)
    val ownerId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_role", nullable = false, length = 16)
    val ownerRole: Role,

    @Column(name = "client_request_id", nullable = false)
    val clientRequestId: UUID,

    @Column(name = "ride_id")
    val rideId: Long? = null,

    @Column(nullable = false, length = 32)
    val category: String,

    @Column(nullable = false, columnDefinition = "text")
    val description: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var status: TicketStatus = TicketStatus.OPEN,

    @Column(columnDefinition = "text")
    var response: String? = null,

    @Column(name = "handled_by")
    var handledBy: Long? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
) {
    fun code(): String = "HT-$id"
}

@Entity
@Table(name = "support_ticket_messages")
class TicketMessageEntity(
    @Column(name = "ticket_id", nullable = false)
    val ticketId: Long,

    @Column(name = "sender_id", nullable = false)
    val senderId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_role", nullable = false, length = 16)
    val senderRole: TicketSenderRole,

    @Column(nullable = false, columnDefinition = "text")
    val body: String,

    @Column(name = "image_url", length = 2048)
    val imageUrl: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
)
