package com.nhom15.datxebackend.vehicle.controller

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.vehicle.dto.AddVehicleImageRequest
import com.nhom15.datxebackend.vehicle.dto.CreateVehicleRequest
import com.nhom15.datxebackend.vehicle.dto.UpdateVehicleRequest
import com.nhom15.datxebackend.vehicle.dto.VehicleImageResponse
import com.nhom15.datxebackend.vehicle.dto.VehicleResponse
import com.nhom15.datxebackend.vehicle.service.VehicleService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/customer/vehicles")
@PreAuthorize("hasRole('CUSTOMER')")
class VehicleController(private val vehicleService: VehicleService) {
    @GetMapping
    fun list(@CurrentUser actor: AuthUser): ApiResponse<List<VehicleResponse>> =
        ApiResponse.ok(vehicleService.list(actor))

    @PostMapping
    fun create(
        @CurrentUser actor: AuthUser,
        @Valid @RequestBody request: CreateVehicleRequest,
    ): ResponseEntity<ApiResponse<VehicleResponse>> = ResponseEntity
        .status(HttpStatus.CREATED)
        .body(ApiResponse.ok(vehicleService.create(actor, request)))

    @PutMapping("/{id}")
    fun update(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateVehicleRequest,
    ): ApiResponse<VehicleResponse> = ApiResponse.ok(vehicleService.update(actor, id, request))

    @PostMapping("/{id}/images")
    fun addImage(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: AddVehicleImageRequest,
    ): ResponseEntity<ApiResponse<VehicleImageResponse>> = ResponseEntity
        .status(HttpStatus.CREATED)
        .body(ApiResponse.ok(vehicleService.addImage(actor, id, request)))

    @DeleteMapping("/{id}/images/{imageId}")
    fun deleteImage(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @PathVariable imageId: Long,
    ): ApiResponse<Unit> {
        vehicleService.deleteImage(actor, id, imageId)
        return ApiResponse.ok()
    }

    @DeleteMapping("/{id}")
    fun delete(@CurrentUser actor: AuthUser, @PathVariable id: Long): ApiResponse<Unit> {
        vehicleService.delete(actor, id)
        return ApiResponse.ok()
    }
}
