package com.nhom15.datxebackend.ride.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.ride.dto.request.ConfirmHandoverRequest
import com.nhom15.datxebackend.ride.dto.request.CreateHandoverReportRequest
import com.nhom15.datxebackend.ride.dto.response.HandoverReportResponse
import com.nhom15.datxebackend.ride.service.RideHandoverService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class RideHandoverController(
    private val rideHandoverService: RideHandoverService,
) {
    @PostMapping("/api/driver/rides/{id}/handover/pickup")
    fun createPickupHandover(
        @CurrentUser actor: AuthUser?,
        @PathVariable id: Long,
        @Valid @RequestBody request: CreateHandoverReportRequest,
    ): ResponseEntity<ApiResponse<HandoverReportResponse>> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        val report = rideHandoverService.createPickup(actor, id, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(report))
    }

    @PostMapping("/api/customer/rides/{id}/handover/pickup/confirm")
    fun confirmPickupHandover(
        @CurrentUser actor: AuthUser?,
        @PathVariable id: Long,
        @Valid @RequestBody request: ConfirmHandoverRequest,
    ): ApiResponse<HandoverReportResponse> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        return ApiResponse.ok(rideHandoverService.confirmPickup(actor, id, request))
    }

    @PostMapping("/api/driver/rides/{id}/handover/dropoff")
    fun createDropoffHandover(
        @CurrentUser actor: AuthUser?,
        @PathVariable id: Long,
        @Valid @RequestBody request: CreateHandoverReportRequest,
    ): ResponseEntity<ApiResponse<HandoverReportResponse>> {
        if (actor == null) {
            throw AppException(ErrorCode.UNAUTHORIZED)
        }
        val report = rideHandoverService.createDropoff(actor, id, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(report))
    }

    @PostMapping("/api/customer/rides/{id}/handover/dropoff/confirm")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun confirmDropoffHandover(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: ConfirmHandoverRequest,
    ): ApiResponse<HandoverReportResponse> =
        ApiResponse.ok(rideHandoverService.confirmDropoff(actor, id, request))
}
