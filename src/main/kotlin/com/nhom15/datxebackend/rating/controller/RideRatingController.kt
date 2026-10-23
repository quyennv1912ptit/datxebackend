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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
@PreAuthorize("hasAnyRole('CUSTOMER','DRIVER')")
class RideRatingController(private val ratingService: RideRatingService) {
    @PostMapping("/api/customer/rides/{id}/rating", "/api/driver/rides/{id}/rating")
    fun rate(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: CreateRideRatingRequest,
    ): ApiResponse<RideRatingResponse> = ApiResponse.ok(ratingService.rate(actor, id, request))
}
