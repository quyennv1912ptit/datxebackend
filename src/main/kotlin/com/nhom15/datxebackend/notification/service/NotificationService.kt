package com.nhom15.datxebackend.notification.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.notification.dto.ReadAllNotificationsResponse
import com.nhom15.datxebackend.notification.dto.UnreadNotificationCountResponse
import com.nhom15.datxebackend.notification.repository.NotificationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class NotificationService(private val notificationRepository: NotificationRepository) {
    @Transactional(readOnly = true)
    fun unreadCount(actor: AuthUser): UnreadNotificationCountResponse = when (actor.role) {
        Role.CUSTOMER -> UnreadNotificationCountResponse(notificationRepository.countUnreadForCustomer(actor.id))
        Role.DRIVER -> UnreadNotificationCountResponse(notificationRepository.countUnreadForDriver(actor.id))
        Role.ADMIN -> throw ForbiddenException(message = "Admin không có thông báo cá nhân")
    }

    @Transactional
    fun markRead(actor: AuthUser, notificationId: Long) {
        val updated = when (actor.role) {
            Role.CUSTOMER -> notificationRepository.markReadForCustomer(notificationId, actor.id, Instant.now())
            Role.DRIVER -> notificationRepository.markReadForDriver(notificationId, actor.id, Instant.now())
            Role.ADMIN -> throw ForbiddenException(message = "Admin không có thông báo cá nhân")
        }
        if (updated == 0) throw NotFoundException(ErrorCode.NOTIFICATION_NOT_FOUND)
    }

    @Transactional
    fun markAllRead(actor: AuthUser): ReadAllNotificationsResponse {
        val updated = when (actor.role) {
            Role.CUSTOMER -> notificationRepository.markAllReadForCustomer(actor.id, Instant.now())
            Role.DRIVER -> notificationRepository.markAllReadForDriver(actor.id, Instant.now())
            Role.ADMIN -> throw ForbiddenException(message = "Admin không có thông báo cá nhân")
        }
        return ReadAllNotificationsResponse(updated)
    }
}
