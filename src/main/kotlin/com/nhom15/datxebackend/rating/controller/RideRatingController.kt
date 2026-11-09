package com.nhom15.datxebackend.rating.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.rating.dto.CreateRideRatingRequest
import com.nhom15.datxebackend.rating.dto.RatingReceivedResponse
import com.nhom15.datxebackend.rating.dto.RideRatingResponse
import com.nhom15.datxebackend.rating.service.RideRatingService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@PreAuthorize("hasAnyRole('CUSTOMER','DRIVER')")
class RideRatingController(
    private val ratingService: RideRatingService,
) {
    @PostMapping(
        "/api/customer/rides/{id}/rating",
        "/api/driver/rides/{id}/rating",
    )
    fun rate(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: CreateRideRatingRequest,
    ): ApiResponse<RideRatingResponse> =
        ApiResponse.ok(ratingService.rate(actor, id, request))

    @GetMapping(
        "/api/customer/ratings/received",
        "/api/driver/ratings/received",
    )
    fun received(
        @CurrentUser actor: AuthUser,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<RatingReceivedResponse> =
        ApiResponse.ok(ratingService.received(actor, page, size))

    @GetMapping(
        "/api/customer/rides/{id}/rating/me",
        "/api/driver/rides/{id}/rating/me",
    )
    fun myRating(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
    ): ApiResponse<RideRatingResponse?> =
        ApiResponse.ok(ratingService.myRating(actor, id))
}