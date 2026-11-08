package com.nhom15.datxebackend.support.ticket

import com.nhom15.datxebackend.support.SupportImage
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Body tạo ticket từ app khách/tài xế. */
data class CreateTicketRequest(
    @field:NotBlank
    @field:Size(max = 32)
    val category: String,

    val rideId: Long? = null,

    @field:NotBlank
    @field:Size(min = 10, max = 4_000)
    val description: String,

    /** Cùng id này khi retry để không tạo ticket trùng. */
    val clientRequestId: UUID = UUID.randomUUID(),
)

data class TicketSummary(
    val id: Long,
    val code: String,
    val rideId: Long?,
    val category: String,
    val description: String,
    val status: TicketStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val ownerId: Long,
    val ownerRole: String,
)

data class TicketMessageDto(
    val id: Long,
    val ticketId: Long,
    val senderId: Long,
    val senderRole: TicketSenderRole,
    val body: String,
    val imageUrl: String?,
    val createdAt: Instant,
)

data class TicketDetail(
    val ticket: TicketSummary,
    val messages: List<TicketMessageDto>,
)

data class SendTicketMessageRequest(
    /** Có thể rỗng nếu chỉ gửi ảnh; service kiểm tra "text hoặc ảnh". */
    @field:Size(max = 4_000)
    val body: String = "",

    /** ADMIN dùng để đổi trạng thái ticket khi gửi; app khách/tài xế bỏ qua. */
    val status: TicketStatus? = null,

    @field:Size(max = 2_048)
    val imageUrl: String? = null,
)

data class AdminTicketSummary(
    val id: Long,
    val code: String,
    val ownerId: Long,
    val ownerName: String?,
    val ownerPhoneNumber: String?,
    val ownerRole: String,
    val category: String,
    val rideId: Long?,
    val status: TicketStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val handledBy: Long?,
)

data class AdminTicketDetail(
    val ticket: AdminTicketSummary,
    val description: String,
    val images: List<SupportImage>,
    val messages: List<TicketMessageDto>,
)
