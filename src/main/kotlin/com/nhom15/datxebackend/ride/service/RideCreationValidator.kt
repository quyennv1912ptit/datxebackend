package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.util.GeoUtils.distanceKm
import com.nhom15.datxebackend.config.properties.ServiceAreaProperties
import com.nhom15.datxebackend.ride.dto.request.CreateRideRequest
import com.nhom15.datxebackend.ride.entity.RideType
import java.time.Instant
import org.springframework.stereotype.Component

@Component
class RideCreationValidator(
    private val serviceArea: ServiceAreaProperties,
) {
    fun validate(request: CreateRideRequest) {
        listOf(request.pickupLat to request.pickupLng, request.dropoffLat to request.dropoffLng).forEach { (lat, lng) ->
            if (!lat.isFinite() || lat !in -85.05112878..85.05112878 || !lng.isFinite() || lng !in -180.0..180.0) {
                throw AppException(
                    ErrorCode.VALIDATION_ERROR, "Tọa độ không hợp lệ",
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
            }
        }
        validateServiceArea(GeoPoint(request.pickupLat, request.pickupLng), GeoPoint(request.dropoffLat, request.dropoffLng))
        validateFields(request)
        validateSchedule(request)
    }

    private fun validateServiceArea(pickup: GeoPoint, dropoff: GeoPoint) {
        if (!serviceArea.enabled) return
        val center = GeoPoint(serviceArea.centerLat, serviceArea.centerLng)
        listOf("Điểm đón" to pickup, "Điểm trả" to dropoff).forEach { (label, point) ->
            if (distanceKm(center, point) > serviceArea.radiusKm) {
                throw AppException(
                    ErrorCode.OUT_OF_SERVICE_AREA,
                    "$label nằm ngoài vùng phục vụ",
                    ErrorCode.OUT_OF_SERVICE_AREA.httpStatus,
                )
            }
        }
    }

    private fun validateFields(request: CreateRideRequest) {
        if (request.pickupAddress.isBlank() || request.pickupAddress.length > 255 ||
            request.dropoffAddress.isBlank() || request.dropoffAddress.length > 255
        ) {
            throw BadRequestException(message = "Địa chỉ phải có nội dung và không vượt quá 255 ký tự")
        }
        if (request.note != null && request.note.length > 500) {
            throw BadRequestException(message = "Ghi chú không được vượt quá 500 ký tự")
        }
    }

    private fun validateSchedule(request: CreateRideRequest) {
        when (request.type) {
            RideType.INSTANT -> {
                if (request.scheduledAt != null) {
                    throw BadRequestException(message = "Ride tức thời không được có scheduledAt")
                }
            }

            RideType.SCHEDULED -> {
                val scheduledAt = request.scheduledAt
                    ?: throw BadRequestException(message = "Ride đặt lịch phải có scheduledAt")
                if (!scheduledAt.isAfter(Instant.now())) {
                    throw BadRequestException(message = "scheduledAt phải nằm trong tương lai")
                }
            }
        }
    }
}
