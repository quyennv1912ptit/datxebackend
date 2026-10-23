package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.ride.dto.request.CreateRideRequest
import com.nhom15.datxebackend.ride.entity.RideType
import java.time.Instant
import org.springframework.stereotype.Component

@Component
class RideCreationValidator {
    fun validate(request: CreateRideRequest) {
        validateFields(request)
        validateSchedule(request)
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
