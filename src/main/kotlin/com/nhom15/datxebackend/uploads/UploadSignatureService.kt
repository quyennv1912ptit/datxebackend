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
            "SUPPORT" -> {
                // Khách, tài xế và admin đều gửi ảnh trong ticket hỗ trợ.
                allowedFormats = CHAT_IMAGE_FORMATS
                CloudinaryFolders.forActor(actor)
            }
            null, "DEFAULT" -> CloudinaryFolders.forActor(actor)
            "CHAT", "CHAT_IMAGE" -> {
                if (actor.role !in setOf(Role.CUSTOMER, Role.DRIVER)) {
                    throw BadRequestException(message = "Chỉ khách hàng hoặc tài xế trong chuyến mới được tải ảnh chat")
                }
                val id = rideId?.takeIf { it > 0 }
                    ?: throw BadRequestException(message = "rideId bắt buộc cho ảnh chat")
                val context = conversationService.requireParticipant(id, actor)
                val conversation = conversationService.requireOpen(context)
                allowedFormats = CHAT_IMAGE_FORMATS
                // ChatService validates against datxe/chat/{conversationId}/{ROLE}/{actorId}/.
                CloudinaryFolders.chat(conversation.id, actor.role, actor.id)
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
        // Chỉ ký đúng các tham số mà client hiện tại gửi kèm (folder, timestamp,
        // allowed_formats). Cloudinary yêu cầu tập tham số được ký phải KHỚP CHÍNH XÁC
        // với tập client gửi lên: nếu thêm public_id/overwrite vào signature trong khi
        // RN client chưa gửi hai field này, mọi upload sẽ bị từ chối "Signature mismatch".
        // Ranh giới bảo mật nằm ở CloudinaryAssetService.validateOwnedImage (kiểm tra
        // cloud name, owner folder và publicId/URL tương ứng khi lưu kết quả), đã được
        // giữ nguyên. Có thể ký thêm public_id/overwrite sau khi client được cập nhật.
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
