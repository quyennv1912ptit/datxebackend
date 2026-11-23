
package com.nhom15.datxebackend.notification.token

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class DeviceTokenService(
    private val deviceTokenRepository: DeviceTokenRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun registerOrUpdate(
        userId: Long,
        role: Role,
        installationId: String,
        fcmToken: String,
        platform: String
    ) {
        requireSupportedRole(role)
        val now = Instant.now()

        val existingByInstallation =
            deviceTokenRepository.findByInstallationId(installationId)

        val existingByToken =
            deviceTokenRepository.findByFcmToken(fcmToken)

        // Dòng dữ liệu tìm được theo installationId hoặc fcmToken mà không thuộc caller
        // là hành vi chiếm quyền. Chặn trước mọi phép ghi: registerOrUpdate không được
        // overwrite ownerId/role hay rebind token của người khác (unregister đã làm đúng).
        requireOwnedByCaller(existingByInstallation, userId, role, "installationId")
        requireOwnedByCaller(existingByToken, userId, role, "fcmToken")

        if (existingByInstallation != null) {
            // Sau hai guard ở trên, mọi bản ghi còn lại đều thuộc caller. Việc xóa ở đây
            // chỉ nhằm trả token đang nằm ở installation khác của chính caller, không
            // bao giờ xóa bản ghi của người thứ ba khi có va chạm token.
            if (
                existingByToken != null &&
                existingByToken.installationId != installationId
            ) {
                deviceTokenRepository.delete(existingByToken)
                deviceTokenRepository.flush()
            }

            existingByInstallation.ownerId = userId
            existingByInstallation.role = role
            existingByInstallation.fcmToken = fcmToken
            existingByInstallation.platform = platform
            existingByInstallation.lastSeenAt = now

            deviceTokenRepository.save(existingByInstallation)
            return
        }

        if (existingByToken != null) {
            existingByToken.installationId = installationId
            existingByToken.ownerId = userId
            existingByToken.role = role
            existingByToken.platform = platform
            existingByToken.lastSeenAt = now

            deviceTokenRepository.save(existingByToken)
            return
        }

        deviceTokenRepository.save(
            DeviceToken(
                installationId = installationId,
                ownerId = userId,
                role = role,
                fcmToken = fcmToken,
                platform = platform,
                lastSeenAt = now
            )
        )
    }

    @Transactional
    fun unregister(
        userId: Long,
        role: Role,
        installationId: String
    ) {
        requireSupportedRole(role)

        val token = deviceTokenRepository
            .findByInstallationIdAndOwnerIdAndRole(
                installationId = installationId,
                ownerId = userId,
                role = role
            )

        if (token != null) {
            deviceTokenRepository.delete(token)
        }
    }

    private fun requireOwnedByCaller(
        existing: DeviceToken?,
        userId: Long,
        role: Role,
        lookup: String
    ) {
        if (existing == null || (existing.ownerId == userId && existing.role == role)) return
        // Không log userId/installationId/fcmToken: tránh lộ PII và token vào log.
        log.warn("Từ chối đăng ký device token: bản ghi theo {} thuộc về chủ khác", lookup)
        throw ForbiddenException(message = "Thiết bị hoặc token này đã được đăng ký bởi tài khoản khác")
    }

    private fun requireSupportedRole(role: Role) {
        if (role == Role.ADMIN) {
            throw BadRequestException(
                ErrorCode.VALIDATION_ERROR,
                "Admin không hỗ trợ device token"
            )
        }
    }
}
