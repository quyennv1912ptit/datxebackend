package com.nhom15.datxebackend.ride.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.ride.entity.HandoverPhase
import com.nhom15.datxebackend.ride.service.*
import org.springframework.web.bind.annotation.*

@RestController
class HandoverCorrectionController(private val service: HandoverCorrectionService) {
    @PostMapping("/api/customer/rides/{id}/handover/{phase}/corrections")
    fun request(@CurrentUser actor: AuthUser, @PathVariable id: Long, @PathVariable phase: String, @RequestBody request: HandoverCorrectionRequest) = ApiResponse.ok(service.request(actor, id, parse(phase), request))
    @PostMapping("/api/driver/rides/{id}/handover/{phase}/revisions")
    fun revise(@CurrentUser actor: AuthUser, @PathVariable id: Long, @PathVariable phase: String, @RequestBody request: ReviseHandoverRequest) = ApiResponse.ok(service.revise(actor, id, parse(phase), request))
    @GetMapping("/api/customer/rides/{id}/handover/{phase}/revisions", "/api/driver/rides/{id}/handover/{phase}/revisions")
    fun history(@CurrentUser actor: AuthUser, @PathVariable id: Long, @PathVariable phase: String) = ApiResponse.ok(service.history(actor, id, parse(phase)))
    private fun parse(phase: String) = runCatching { HandoverPhase.valueOf(phase.uppercase()) }.getOrElse { throw com.nhom15.datxebackend.common.exception.BadRequestException(message = "Loại biên bản không hợp lệ") }
}
