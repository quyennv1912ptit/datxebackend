package com.nhom15.datxebackend.notification.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.notification.dto.NotificationHistoryItem
import com.nhom15.datxebackend.notification.service.NotificationHistoryService
import com.nhom15.datxebackend.notification.dto.ReadAllNotificationsResponse
import com.nhom15.datxebackend.notification.dto.UnreadNotificationCountResponse
import com.nhom15.datxebackend.notification.service.NotificationService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/notifications")
class NotificationHistoryController(
    private val historyService: NotificationHistoryService,
    private val notificationService: NotificationService,
) {
    @GetMapping
    fun list(@CurrentUser actor: AuthUser): ApiResponse<List<NotificationHistoryItem>> =
        ApiResponse.ok(historyService.list(actor))

    @GetMapping("/unread-count")
    fun unreadCount(@CurrentUser actor: AuthUser): ApiResponse<UnreadNotificationCountResponse> =
        ApiResponse.ok(notificationService.unreadCount(actor))

    @PutMapping("/{id}/read")
    fun markRead(@CurrentUser actor: AuthUser, @PathVariable id: Long): ApiResponse<Unit> {
        notificationService.markRead(actor, id)
        return ApiResponse.ok()
    }

    @PutMapping("/read-all")
    fun markAllRead(@CurrentUser actor: AuthUser): ApiResponse<ReadAllNotificationsResponse> =
        ApiResponse.ok(notificationService.markAllRead(actor))
}
