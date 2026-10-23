package com.nhom15.datxebackend.vehicle.service

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import com.nhom15.datxebackend.vehicle.dto.AddVehicleImageRequest
import com.nhom15.datxebackend.vehicle.dto.CreateVehicleRequest
import com.nhom15.datxebackend.vehicle.dto.UpdateVehicleRequest
import com.nhom15.datxebackend.vehicle.dto.VehicleImageResponse
import com.nhom15.datxebackend.vehicle.dto.VehicleResponse
import com.nhom15.datxebackend.vehicle.repository.JdbcVehicleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class VehicleService(
    private val vehicleRepository: JdbcVehicleRepository,
    private val cloudinaryAssetService: CloudinaryAssetService,
) {
    companion object {
        const val MAX_IMAGES_PER_VEHICLE = 5
    }

    @Transactional(readOnly = true)
    fun list(actor: AuthUser): List<VehicleResponse> {
        requireCustomer(actor)
        return vehicleRepository.findAllByOwnerId(actor.id)
    }

    @Transactional
    fun create(actor: AuthUser, request: CreateVehicleRequest): VehicleResponse {
        requireCustomer(actor)
        return vehicleRepository.create(actor.id, request)
    }

    @Transactional
    fun delete(actor: AuthUser, id: Long) {
        requireCustomer(actor)
        vehicleRepository.deleteByIdAndOwnerId(id, actor.id)
            .forEach(cloudinaryAssetService::deleteAfterCommit)
    }

    @Transactional
    fun update(actor: AuthUser, id: Long, request: UpdateVehicleRequest): VehicleResponse {
        requireCustomer(actor)
        return vehicleRepository.updateByIdAndOwnerId(id, actor.id, request)
    }

    @Transactional
    fun addImage(actor: AuthUser, vehicleId: Long, request: AddVehicleImageRequest): VehicleImageResponse {
        requireCustomer(actor)
        if (!vehicleRepository.lockOwnedVehicle(vehicleId, actor.id)) {
            throw NotFoundException(ErrorCode.VEHICLE_NOT_FOUND)
        }
        if (vehicleRepository.countImages(vehicleId) >= MAX_IMAGES_PER_VEHICLE) {
            throw ConflictException(message = "Mỗi xe chỉ được có tối đa $MAX_IMAGES_PER_VEHICLE ảnh")
        }
        cloudinaryAssetService.validateCustomerImage(actor, request.secureUrl, request.publicId)
        return vehicleRepository.addImage(vehicleId, request.secureUrl.trim(), request.publicId.trim())
    }

    @Transactional
    fun deleteImage(actor: AuthUser, vehicleId: Long, imageId: Long) {
        requireCustomer(actor)
        val publicId = vehicleRepository.deleteImage(vehicleId, imageId, actor.id)
        cloudinaryAssetService.deleteAfterCommit(publicId)
    }

    private fun requireCustomer(actor: AuthUser) {
        if (actor.role != Role.CUSTOMER) throw ForbiddenException(message = "Chỉ khách hàng được quản lý xe")
    }
}
