package com.nhom15.datxebackend.uploads

import com.cloudinary.Cloudinary
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.URI

object CloudinaryFolders {
    fun forActor(actor: AuthUser): String = forActor(actor.role, actor.id)

    fun forActor(role: Role, id: Long): String = "app_dat_xe/${role.name.lowercase()}/$id"

    fun driverDocuments(driverId: Long): String = "datxe/driver-documents/$driverId"

    fun chat(rideId: Long): String = "datxe/chat/$rideId"

    fun handover(rideId: Long): String = "datxe/handover/$rideId"
}

@Service
class CloudinaryAssetService(private val cloudinary: Cloudinary) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun validateCustomerImage(actor: AuthUser, secureUrl: String, publicId: String) {
        requireOwnedAsset(publicId, "${CloudinaryFolders.forActor(actor)}/")
        validateCloudinaryImage(secureUrl, publicId)
    }

    fun validateHandoverImage(rideId: Long, secureUrl: String, publicId: String) {
        requireOwnedAsset(publicId, "${CloudinaryFolders.handover(rideId)}/")
        validateCloudinaryImage(secureUrl, publicId)
    }

    fun requireOwnedAsset(publicId: String, expectedFolder: String): String {
        val folder = expectedFolder.trim().trimEnd('/')
        val normalizedPublicId = publicId.trim()
        if (!normalizedPublicId.startsWith("$folder/") ||
            normalizedPublicId.substringAfter("$folder/").isBlank() ||
            normalizedPublicId.split('/').any { it == "." || it == ".." } ||
            normalizedPublicId.contains('\\')
        ) {
            throw BadRequestException(message = "publicId không thuộc thư mục upload được cấp")
        }
        return normalizedPublicId
    }

    fun validateCloudinaryImage(secureUrl: String, publicId: String) {
        val normalizedPublicId = publicId.trim()
        val uri = runCatching { URI(secureUrl.trim()) }.getOrNull()
            ?: throw BadRequestException(message = "secureUrl ảnh không hợp lệ")
        val cloudName = cloudinary.config.cloudName
        if (uri.scheme != "https" || uri.host != "res.cloudinary.com" ||
            uri.rawQuery != null || uri.rawFragment != null || cloudName.isNullOrBlank()
        ) {
            throw BadRequestException(message = "Ảnh phải là secure URL từ Cloudinary")
        }

        val path = uri.path.split('/').filter(String::isNotEmpty)
        val uploadIndex = path.indexOf("upload")
        val versionIndex = path.indexOfFirst { it.matches(Regex("v\\d+")) }
        if (path.getOrNull(0) != cloudName || path.getOrNull(1) != "image" || uploadIndex != 2 ||
            versionIndex <= uploadIndex || versionIndex == path.lastIndex
        ) {
            throw BadRequestException(message = "secureUrl ảnh không khớp với Cloudinary")
        }

        val uploadedPath = path.drop(versionIndex + 1).joinToString("/")
        val actualPublicId = uploadedPath.substringBeforeLast('.', missingDelimiterValue = "")
        if (actualPublicId != normalizedPublicId) {
            throw BadRequestException(message = "secureUrl không khớp với publicId")
        }
    }

    fun deleteAfterCommit(publicId: String?) {
        if (publicId.isNullOrBlank()) return
        val deletion = Runnable {
            try {
                cloudinary.uploader().destroy(
                    publicId,
                    mapOf("invalidate" to true, "resource_type" to "image"),
                )
            } catch (e: Exception) {
                log.warn("Không xóa được ảnh Cloudinary {} sau commit", publicId, e)
            }
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = deletion.run()
            })
        } else {
            deletion.run()
        }
    }

    fun deleteNowOrThrow(publicId: String) {
        val response = cloudinary.uploader().destroy(
            publicId,
            mapOf("invalidate" to true, "resource_type" to "image"),
        )
        val result = response["result"]?.toString()
        if (result != "ok" && result != "not found") {
            throw IllegalStateException("Cloudinary không xác nhận đã xóa asset")
        }
    }
}
