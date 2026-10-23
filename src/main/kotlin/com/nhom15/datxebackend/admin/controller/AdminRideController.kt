package com.nhom15.datxebackend.admin.controller

import com.nhom15.datxebackend.chat.dto.MessagePage
import com.nhom15.datxebackend.chat.service.ChatService
import com.nhom15.datxebackend.admin.dto.AdminAssignRideRequest
import com.nhom15.datxebackend.ride.dto.request.RideListFilter
import com.nhom15.datxebackend.ride.dto.request.CancelRideRequest
import com.nhom15.datxebackend.ride.dto.response.RideSummaryResponse
import com.nhom15.datxebackend.ride.dto.response.RideDetailResponse
import com.nhom15.datxebackend.ride.dto.response.RideStatusResponse
import com.nhom15.datxebackend.admin.service.AdminRideService
import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/rides")
@PreAuthorize("hasRole('ADMIN')")
class AdminRideController(
    private val adminRideService: AdminRideService,
    private val chatService: ChatService,
) {
    @GetMapping
    fun listRides(
        @CurrentUser actor: AuthUser,
        @Valid @ModelAttribute filter: RideListFilter,
    ): ApiResponse<PageResponse<RideSummaryResponse>> = ApiResponse.ok(adminRideService.listRides(actor, filter))

    @GetMapping("/{id}")
    fun getRideDetail(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
    ): ApiResponse<RideDetailResponse> = ApiResponse.ok(adminRideService.getRideDetail(actor, id))

    @GetMapping("/{id}/chat")
    fun chatHistory(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @RequestParam(required = false) beforeId: Long?,
        @RequestParam(required = false) afterId: Long?,
        @RequestParam(defaultValue = "30") size: Int,
    ): ApiResponse<MessagePage> =
        ApiResponse.ok(chatService.adminMessages(id, actor, beforeId, afterId, size.coerceIn(1, 100)))

    @PostMapping("/{id}/assign")
    fun assignDriver(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminAssignRideRequest,
    ): ApiResponse<RideStatusResponse> = ApiResponse.ok(adminRideService.assignDriver(actor, id, request))

    @PostMapping("/{id}/cancel")
    fun cancelRide(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: CancelRideRequest,
    ): ApiResponse<RideStatusResponse> = ApiResponse.ok(adminRideService.cancelRide(actor, id, request))
}
