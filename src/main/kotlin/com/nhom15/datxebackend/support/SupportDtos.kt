package com.nhom15.datxebackend.support

import java.time.Instant
import java.util.UUID

data class SupportImage(val fileUrl: String, val publicId: String)
data class CreateSupportTicket(
    val clientRequestId: UUID,
    val rideId: Long? = null,
    val category: String,
    val description: String,
    val images: List<SupportImage> = emptyList(),
)
data class UpdateSupportTicket(val status: String, val response: String?, val expectedUpdatedAt: Instant? = null)
data class SupportTicket(
    val id: Long, val code: String, val rideId: Long?, val category: String,
    val description: String, val status: String, val response: String?,
    val images: List<SupportImage>, val createdAt: Instant, val updatedAt: Instant,
    val ownerId: Long, val ownerRole: String,
)
