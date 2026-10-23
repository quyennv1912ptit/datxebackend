package com.nhom15.datxebackend.ride.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.ride.dto.request.ArriveDestinationRequest
import com.nhom15.datxebackend.ride.dto.request.CompleteRideRequest
import com.nhom15.datxebackend.ride.dto.request.DriverArrivedRequest
import com.nhom15.datxebackend.ride.dto.request.DriverStartArrivingRequest
import com.nhom15.datxebackend.ride.dto.request.StartTripRequest
import com.nhom15.datxebackend.ride.dto.response.RideStatusResponse
import com.nhom15.datxebackend.ride.service.RideProgressService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/driver/rides")
class RideProgressController(
    private val rideProgressService: RideProgressService,
) {
    @PostMapping("/{id}/start-arriving")
    fun startArriving(
        @CurrentUser actor: AuthUser?,
        @PathVariable id: Long,
        @Valid @RequestBody request: DriverStartArrivingRequest,
    ): ApiResponse<RideStatusResponse> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        return ApiResponse.ok(rideProgressService.startArriving(actor, id, request))
    }

    @PostMapping("/{id}/arrived")
    fun arrivePickup(
        @CurrentUser actor: AuthUser?,
        @PathVariable id: Long,
        @Valid @RequestBody request: DriverArrivedRequest,
    ): ApiResponse<RideStatusResponse> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        return ApiResponse.ok(rideProgressService.arrivePickup(actor, id, request))
    }

    @PostMapping("/{id}/start")
    fun startTrip(
        @CurrentUser actor: AuthUser?,
        @PathVariable id: Long,
        @Valid @RequestBody request: StartTripRequest,
    ): ApiResponse<RideStatusResponse> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        return ApiResponse.ok(rideProgressService.startTrip(actor, id, request))
    }

    @PostMapping("/{id}/arrive-destination")
    fun arriveDestination(
        @CurrentUser actor: AuthUser?,
        @PathVariable id: Long,
        @Valid @RequestBody request: ArriveDestinationRequest,
    ): ApiResponse<RideStatusResponse> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        return ApiResponse.ok(rideProgressService.arriveDestination(actor, id, request))
    }

    @PostMapping("/{id}/complete")
    fun completeRide(
        @CurrentUser actor: AuthUser?,
        @PathVariable id: Long,
        @Valid @RequestBody request: CompleteRideRequest,
    ): ApiResponse<RideStatusResponse> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        return ApiResponse.ok(rideProgressService.completeRide(actor, id, request))
    }
}
