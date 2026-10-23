package com.nhom15.datxebackend.uploads

import com.cloudinary.Cloudinary
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.chat.service.ConversationService
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class UploadSignatureService(
    private val cloudinary: Cloudinary,
    private val conversationService: ConversationService,
) {
    fun create(actor: AuthUser, purpose: String?, rideId: Long?): UploadSignatureResponse {
        val timestamp = Instant.now().epochSecond
        var allowedFormats: List<String>? = null
        val folder = when (purpose?.uppercase()) {
            null, "DEFAULT" -> CloudinaryFolders.forActor(actor)
            "DRIVER_DOCUMENT" -> {
                if (actor.role != Role.DRIVER) {
                    throw BadRequestException(message = "Chỉ tài xế mới được ký upload giấy tờ")
                }
                CloudinaryFolders.driverDocuments(actor.id)
            }
            "CHAT", "CHAT_IMAGE" -> {
                if (actor.role !in setOf(Role.CUSTOMER, Role.DRIVER)) {
                    throw BadRequestException(message = "Chỉ khách hàng hoặc tài xế trong chuyến mới được tải ảnh chat")
                }
                val id = rideId?.takeIf { it > 0 }
                    ?: throw BadRequestException(message = "rideId bắt buộc cho ảnh chat")
                val context = conversationService.requireParticipant(id, actor)
                conversationService.requireOpen(context)
                allowedFormats = CHAT_IMAGE_FORMATS
                CloudinaryFolders.chat(id)
            }
            "HANDOVER", "HANDOVER_IMAGE" -> {
                if (actor.role != Role.DRIVER) {
                    throw BadRequestException(message = "Chỉ tài xế được tải ảnh biên bản bàn giao")
                }
                val id = rideId?.takeIf { it > 0 }
                    ?: throw BadRequestException(message = "rideId bắt buộc cho ảnh bàn giao")
                val context = conversationService.requireParticipant(id, actor)
                conversationService.requireOpen(context)
                allowedFormats = HANDOVER_IMAGE_FORMATS
                CloudinaryFolders.handover(id)
            }
            else -> throw BadRequestException(message = "Mục đích upload không hợp lệ")
        }
        val cloudinaryConfig = cloudinary.config
        val params = mapOf<String, Any>(
            "folder" to folder,
            "timestamp" to timestamp
        ).toMutableMap().apply {
            allowedFormats?.let { put("allowed_formats", it.joinToString(",")) }
        }
        return UploadSignatureResponse(
            signature = cloudinary.apiSignRequest(params, cloudinaryConfig.apiSecret),
            timestamp = timestamp,
            apiKey = cloudinaryConfig.apiKey,
            cloudName = cloudinaryConfig.cloudName,
            folder = folder,
            allowedFormats = allowedFormats,
        )
    }

    private companion object {
        val CHAT_IMAGE_FORMATS = listOf("jpg", "jpeg", "png", "webp")
        val HANDOVER_IMAGE_FORMATS = listOf("jpg", "jpeg", "png", "webp")
    }
}
