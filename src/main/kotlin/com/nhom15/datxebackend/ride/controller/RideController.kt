package com.nhom15.datxebackend.ride.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.ride.dto.request.CancelRideRequest
import com.nhom15.datxebackend.ride.dto.request.CreateRideRequest
import com.nhom15.datxebackend.ride.dto.request.EstimateRideRequest
import com.nhom15.datxebackend.ride.dto.request.RideListFilter
import com.nhom15.datxebackend.ride.dto.response.CreateRideResponse
import com.nhom15.datxebackend.ride.dto.response.EstimateRideResponse
import com.nhom15.datxebackend.ride.dto.response.RideDetailResponse
import com.nhom15.datxebackend.ride.dto.response.RideStatusResponse
import com.nhom15.datxebackend.ride.dto.response.RideSummaryResponse
import com.nhom15.datxebackend.ride.service.RideEstimateService
import com.nhom15.datxebackend.ride.service.RideQueryService
import com.nhom15.datxebackend.ride.service.RideService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class RideController(
    private val rideService: RideService,
    private val rideQueryService: RideQueryService,
    private val rideEstimateService: RideEstimateService,
) {
    @PostMapping("/api/customer/rides/estimate")
    fun estimateRide(
        @CurrentUser actor: AuthUser?,
        @Valid @RequestBody request: EstimateRideRequest,
    ): ApiResponse<EstimateRideResponse> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        return ApiResponse.ok(rideEstimateService.estimateRide(actor, request))
    }

    @GetMapping("/api/customer/rides/{id}", "/api/driver/rides/{id}")
    fun getRideDetail(
        @CurrentUser actor: AuthUser?,
        @PathVariable id: Long,
    ): ApiResponse<RideDetailResponse> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        return ApiResponse.ok(rideQueryService.getDetail(actor, id))
    }

    @GetMapping("/api/customer/rides/active", "/api/driver/rides/active")
    fun getActiveRide(
        @CurrentUser actor: AuthUser?,
    ): ApiResponse<RideDetailResponse?> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        return ApiResponse.ok(rideQueryService.getActiveRide(actor))
    }

    @GetMapping("/api/customer/rides", "/api/driver/rides")
    fun listMyRides(
        @CurrentUser actor: AuthUser?,
        @Valid @ModelAttribute filter: RideListFilter,
    ): ApiResponse<PageResponse<RideSummaryResponse>> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        return ApiResponse.ok(rideQueryService.listMyRides(actor, filter))
    }

    @PostMapping("/api/customer/rides")
    fun createRide(
        @CurrentUser actor: AuthUser,
        @Valid @RequestBody request: CreateRideRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<ApiResponse<CreateRideResponse>> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ApiResponse.ok(rideService.createRide(actor, request, idempotencyKey)))

    @PostMapping("/api/customer/rides/{id}/cancel", "/api/driver/rides/{id}/cancel")
    fun cancelRide(
        @CurrentUser actor: AuthUser?,
        @PathVariable id: Long,
        @Valid @RequestBody request: CancelRideRequest,
    ): ApiResponse<RideStatusResponse> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        return ApiResponse.ok(rideService.cancelRide(actor, id, request))
    }
}
