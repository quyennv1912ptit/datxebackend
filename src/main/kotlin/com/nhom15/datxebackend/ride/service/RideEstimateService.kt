package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.port.CustomerVehicleQueryPort
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.pricing.api.RideQuoteProvider
import com.nhom15.datxebackend.pricing.model.RideQuoteInput
import com.nhom15.datxebackend.ride.dto.request.EstimateRideRequest
import com.nhom15.datxebackend.ride.dto.response.EstimateRideResponse
import com.nhom15.datxebackend.voucher.service.VoucherService
import java.time.Instant
import org.springframework.stereotype.Service

@Service
class RideEstimateService(
    private val quoteProvider: RideQuoteProvider,
    private val voucherService: VoucherService,
    private val vehicleQueryPort: CustomerVehicleQueryPort,
    private val rateLimiter: RedisRateLimiter,
) {
    fun estimateRide(actor: AuthUser, request: EstimateRideRequest): EstimateRideResponse {
        if (actor.role != Role.CUSTOMER) {
            throw ForbiddenException(message = "Chỉ khách hàng mới có thể báo giá chuyến đi")
        }
        validateEstimate(request)

        val now = Instant.now()
        val scheduledAt = request.scheduledAt
        if (scheduledAt != null && !scheduledAt.isAfter(now)) {
            throw BadRequestException(message = "scheduledAt phải nằm trong tương lai")
        }
        val vehicle = vehicleQueryPort.findOwnedVehicle(request.vehicleId, actor.id)
            ?: throw NotFoundException(ErrorCode.VEHICLE_NOT_FOUND)
        rateLimiter.check(RedisKeys.rideQuoteRate(actor.id.toString()), RIDE_QUOTE_LIMIT, RIDE_QUOTE_WINDOW)
        val input = RideQuoteInput(
            pickup = GeoPoint(request.pickupLat, request.pickupLng),
            dropoff = GeoPoint(request.dropoffLat, request.dropoffLng),
            pricingAt = scheduledAt ?: now,
            vehicleType = vehicle.vehicleType,
        )
        val quote = quoteProvider.quote(input)
        val voucher = voucherService.calculate(actor, request.voucherCode, quote.estimatedFare)
        return RideResponseMapper.estimate(quote, voucher?.discountAmount ?: 0)
    }

    private fun validateEstimate(request: EstimateRideRequest) {
        if (request.vehicleId <= 0) {
            throw BadRequestException(message = "vehicleId phải lớn hơn 0")
        }
        if (request.pickupAddress.isBlank() || request.pickupAddress.length > 255) {
            throw BadRequestException(message = "Địa chỉ đón phải có nội dung và không vượt quá 255 ký tự")
        }
        if (request.dropoffAddress.isBlank() || request.dropoffAddress.length > 255) {
            throw BadRequestException(message = "Địa chỉ đến phải có nội dung và không vượt quá 255 ký tự")
        }
        if (!request.pickupLat.isFinite() || request.pickupLat < -90.0 || request.pickupLat > 90.0) {
            throw BadRequestException(message = "pickupLat phải hữu hạn và từ -90 đến 90")
        }
        if (!request.pickupLng.isFinite() || request.pickupLng < -180.0 || request.pickupLng > 180.0) {
            throw BadRequestException(message = "pickupLng phải hữu hạn và từ -180 đến 180")
        }
        if (!request.dropoffLat.isFinite() || request.dropoffLat < -90.0 || request.dropoffLat > 90.0) {
            throw BadRequestException(message = "dropoffLat phải hữu hạn và từ -90 đến 90")
        }
        if (!request.dropoffLng.isFinite() || request.dropoffLng < -180.0 || request.dropoffLng > 180.0) {
            throw BadRequestException(message = "dropoffLng phải hữu hạn và từ -180 đến 180")
        }
    }

    private companion object {
        const val RIDE_QUOTE_LIMIT = 30L
        val RIDE_QUOTE_WINDOW = java.time.Duration.ofMinutes(1)
    }
}
