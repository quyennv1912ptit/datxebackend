package com.nhom15.datxebackend.matching.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.matching.service.RideAcceptanceService
import com.nhom15.datxebackend.notification.dto.RideOfferPayload
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/driver/offers")
@PreAuthorize("hasRole('DRIVER')")
class OfferController(
    private val acceptanceService: RideAcceptanceService
) {

    @GetMapping("/pending")
    fun pendingOffer(@CurrentUser user: AuthUser): ApiResponse<RideOfferPayload?> =
        ApiResponse.ok(acceptanceService.pendingOffer(user.id))

    /** 200 nếu nhận được chuyến, 409 nếu lời mời đã hết hạn hoặc chuyến đã giao người khác. */
    @PostMapping("/{rideId}/accept")
    fun driverAcceptedOffer(
        @PathVariable rideId: Long,
        @CurrentUser user: AuthUser
    ): ApiResponse<String> {
        if (!acceptanceService.accept(rideId, user.id)) {
            throw ConflictException(message = "Lời mời đã hết hạn hoặc chuyến đã giao cho người khác.")
        }
        return ApiResponse.ok("Nhận chuyến thành công")
    }
    /** Luôn trả về 200 */
    @PostMapping("/{rideId}/reject")
    fun driverRejectedOffer(
        @PathVariable rideId: Long,
        @CurrentUser user: AuthUser
    ): ApiResponse<Unit> {
        acceptanceService.reject(rideId, user.id)
        return ApiResponse.ok()
    }
}
