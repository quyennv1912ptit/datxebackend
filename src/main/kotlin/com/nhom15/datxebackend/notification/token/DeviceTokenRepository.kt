
package com.nhom15.datxebackend.notification.token

import com.nhom15.datxebackend.common.enums.Role
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DeviceTokenRepository : JpaRepository<DeviceToken, Long> {

    fun findByOwnerId(ownerId: Long): List<DeviceToken>

    fun findByRoleAndOwnerId(
        role: Role,
        ownerId: Long
    ): List<DeviceToken>

    fun findByFcmToken(fcmToken: String): DeviceToken?

    fun findByInstallationId(
        installationId: String
    ): DeviceToken?

    fun findByInstallationIdAndOwnerIdAndRole(
        installationId: String,
        ownerId: Long,
        role: Role
    ): DeviceToken?

    fun deleteAllByRoleAndOwnerId(role: Role, ownerId: Long): Long
}
