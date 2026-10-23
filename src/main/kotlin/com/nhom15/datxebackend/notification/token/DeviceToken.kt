
package com.nhom15.datxebackend.notification.token

import com.nhom15.datxebackend.common.entity.BaseEntity
import com.nhom15.datxebackend.common.enums.Role
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

@Entity
@Table(
    name = "device_tokens",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_device_token",
            columnNames = ["fcm_token"]
        ),
        UniqueConstraint(
            name = "uk_device_installation",
            columnNames = ["installation_id"]
        )
    ],
    indexes = [
        Index(
            name = "idx_device_owner",
            columnList = "role, owner_id"
        )
    ]
)
class DeviceToken(
    @Column(name = "installation_id", nullable = false, length = 36)
    var installationId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    var role: Role,

    @Column(name = "owner_id", nullable = false)
    var ownerId: Long,

    @Column(name = "fcm_token", nullable = false, length = 255)
    var fcmToken: String,

    @Column(name = "platform", nullable = false, length = 20)
    var platform: String,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant = Instant.now()
) : BaseEntity()
