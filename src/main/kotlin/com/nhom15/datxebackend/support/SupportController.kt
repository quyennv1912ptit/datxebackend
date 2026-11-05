package com.nhom15.datxebackend.support

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
class SupportController(private val service: SupportService) {
    @PostMapping("/api/support/tickets")
    @PreAuthorize("hasAnyRole('CUSTOMER','DRIVER')")
    fun create(@CurrentUser actor: AuthUser, @RequestBody request: CreateSupportTicket) = ApiResponse.ok(service.create(actor, request))

    @GetMapping("/api/support/tickets", "/api/admin/support/tickets")
    fun list(@CurrentUser actor: AuthUser, @RequestParam(defaultValue = "0") page: Int, @RequestParam(required = false) status: String?) = ApiResponse.ok(service.list(actor, page, status))

    @GetMapping("/api/support/tickets/{id}", "/api/admin/support/tickets/{id}")
    fun detail(@CurrentUser actor: AuthUser, @PathVariable id: Long) = ApiResponse.ok(service.detail(actor, id))

    @PatchMapping("/api/admin/support/tickets/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    fun update(@CurrentUser actor: AuthUser, @PathVariable id: Long, @RequestBody request: UpdateSupportTicket) = ApiResponse.ok(service.update(actor, id, request))
}
