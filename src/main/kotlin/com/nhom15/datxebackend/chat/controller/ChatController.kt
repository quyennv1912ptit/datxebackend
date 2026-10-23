package com.nhom15.datxebackend.chat.controller

import com.nhom15.datxebackend.chat.dto.MarkReadRequest
import com.nhom15.datxebackend.chat.dto.MarkReadResponse
import com.nhom15.datxebackend.chat.dto.MessagePage
import com.nhom15.datxebackend.chat.dto.SendMessageRequest
import com.nhom15.datxebackend.chat.service.ChatService
import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/chat/rides")
@PreAuthorize("hasAnyRole('CUSTOMER','DRIVER')")
class ChatController(private val chatService: ChatService) {
    @GetMapping("/{rideId}/messages")
    fun messages(
        @CurrentUser actor: AuthUser,
        @PathVariable rideId: Long,
        @RequestParam(required = false) beforeId: Long?,
        @RequestParam(required = false) afterId: Long?,
        @RequestParam(defaultValue = "30") size: Int,
    ): ApiResponse<MessagePage> = ApiResponse.ok(
        chatService.messages(rideId, actor, beforeId, afterId, size.coerceIn(1, 100)),
    )

    @PostMapping("/{rideId}/messages")
    fun send(
        @CurrentUser actor: AuthUser,
        @PathVariable rideId: Long,
        @Valid @RequestBody request: SendMessageRequest,
    ): ResponseEntity<ApiResponse<com.nhom15.datxebackend.chat.dto.ChatMessageDto>> {
        val result = chatService.send(rideId, actor, request)
        val status = if (result.duplicated) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(ApiResponse.ok(result.message))
    }

    @PutMapping("/{rideId}/read")
    fun markRead(
        @CurrentUser actor: AuthUser,
        @PathVariable rideId: Long,
        @Valid @RequestBody request: MarkReadRequest,
    ): ApiResponse<MarkReadResponse> =
        ApiResponse.ok(chatService.markRead(rideId, actor, request.upToMessageId))
}
