package com.nhom15.datxebackend.support.ticket

import com.nhom15.datxebackend.admin.dto.AdminSearchFilter
import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/tickets")
@PreAuthorize("hasAnyRole('CUSTOMER','DRIVER')")
class TicketController(private val service: TicketService) {
    @PostMapping
    fun create(
        @CurrentUser actor: AuthUser,
        @Valid @RequestBody request: CreateTicketRequest,
    ): ApiResponse<TicketSummary> = ApiResponse.ok(service.create(actor, request))

    @GetMapping
    fun list(
        @CurrentUser actor: AuthUser,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(required = false) status: TicketStatus?,
    ): ApiResponse<List<TicketSummary>> = ApiResponse.ok(service.list(actor, page, status))

    @GetMapping("/{id}")
    fun detail(@CurrentUser actor: AuthUser, @PathVariable id: Long): ApiResponse<TicketDetail> =
        ApiResponse.ok(service.detail(actor, id))

    @GetMapping("/{id}/messages")
    fun messages(@CurrentUser actor: AuthUser, @PathVariable id: Long): ApiResponse<List<TicketMessageDto>> =
        ApiResponse.ok(service.messages(actor, id))

    @PostMapping("/{id}/messages")
    fun sendMessage(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: SendTicketMessageRequest,
    ): ApiResponse<TicketMessageDto> = ApiResponse.ok(service.sendUserMessage(actor, id, request))
}

@RestController
@RequestMapping("/api/admin/tickets")
@PreAuthorize("hasRole('ADMIN')")
class AdminTicketController(private val service: TicketService) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: TicketStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @org.springframework.web.bind.annotation.ModelAttribute filter: AdminSearchFilter,
        @RequestParam(required = false) ownerRole: String?,
        @RequestParam(required = false) category: String?,
    ): ApiResponse<PageResponse<AdminTicketSummary>> = ApiResponse.ok(service.adminList(status, page, size, filter, ownerRole, category))

    @GetMapping("/{id}")
    fun detail(@CurrentUser admin: AuthUser, @PathVariable id: Long): ApiResponse<AdminTicketDetail> =
        ApiResponse.ok(service.adminDetail(admin, id))

    /** Gọi ngay khi admin mở ticket để claim, tránh hai admin cùng trả lời. */
    @PostMapping("/{id}/claim")
    fun claim(@CurrentUser admin: AuthUser, @PathVariable id: Long): ApiResponse<AdminTicketDetail> =
        ApiResponse.ok(service.claim(admin, id))

    @PostMapping("/{id}/messages")
    fun sendMessage(
        @CurrentUser admin: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: SendTicketMessageRequest,
    ): ApiResponse<TicketMessageDto> = ApiResponse.ok(service.sendAdminMessage(admin, id, request))
}
