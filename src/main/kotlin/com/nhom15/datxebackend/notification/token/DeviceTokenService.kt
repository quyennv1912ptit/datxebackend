
package com.nhom15.datxebackend.notification.token

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class DeviceTokenService(
    private val deviceTokenRepository: DeviceTokenRepository
) {

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

        if (existingByInstallation != null) {
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

    private fun requireSupportedRole(role: Role) {
        if (role == Role.ADMIN) {
            throw BadRequestException(
                ErrorCode.VALIDATION_ERROR,
                "Admin không hỗ trợ device token"
            )
        }
    }
}
