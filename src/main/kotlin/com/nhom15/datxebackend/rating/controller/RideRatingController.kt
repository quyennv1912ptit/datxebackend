package com.nhom15.datxebackend.rating.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.rating.dto.CreateRideRatingRequest
import com.nhom15.datxebackend.rating.dto.RideRatingResponse
import com.nhom15.datxebackend.rating.service.RideRatingService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
@PreAuthorize("hasAnyRole('CUSTOMER','DRIVER')")
class RideRatingController(private val ratingService: RideRatingService) {
    @GetMapping("/api/customer/rides/{id}/driver-reviews")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun driverReviews(@CurrentUser actor: AuthUser, @PathVariable id: Long) =
        ApiResponse.ok(ratingService.reviewsForRide(actor, id))

    @GetMapping("/api/driver/profile/reviews")
    @PreAuthorize("hasRole('DRIVER')")
    fun myReviews(@CurrentUser actor: AuthUser) = ApiResponse.ok(ratingService.driverReviews(actor.id))

    @PostMapping("/api/customer/rides/{id}/rating", "/api/driver/rides/{id}/rating")
    fun rate(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: CreateRideRatingRequest,
    ): ApiResponse<RideRatingResponse> = ApiResponse.ok(ratingService.rate(actor, id, request))
}
