package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.port.CustomerVehicleQueryPort
import com.nhom15.datxebackend.common.port.CustomerVehicleSnapshot
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisRateLimiter
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.pricing.api.RideQuoteProvider
import com.nhom15.datxebackend.pricing.model.RideQuoteInput
import com.nhom15.datxebackend.ride.dto.request.CancelRideRequest
import com.nhom15.datxebackend.ride.dto.request.CreateRideRequest
import com.nhom15.datxebackend.ride.dto.response.CreateRideResponse
import com.nhom15.datxebackend.ride.dto.response.RideStatusResponse
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.repository.RideCreateIdempotencyRepository
import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
import com.nhom15.datxebackend.ride.service.state.RideActor
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.ride.service.state.RideTransitionCommand
import com.nhom15.datxebackend.ride.service.state.RideTransitionEvent
import com.nhom15.datxebackend.ride.service.state.customerRideActor
import com.nhom15.datxebackend.voucher.service.VoucherService
import java.time.Instant
import java.security.MessageDigest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RideService(
    private val rideRepository: RideRepository,
    private val vehicleQueryPort: CustomerVehicleQueryPort,
    private val quoteProvider: RideQuoteProvider,
    private val rideRoutes: com.nhom15.datxebackend.routing.service.RideRouteService,
    private val stateMachine: RideStateMachine,
    private val voucherService: VoucherService,
    private val creationValidator: RideCreationValidator,
    private val customerRepository: CustomerRepository,
    private val idempotencyRepository: RideCreateIdempotencyRepository,
    private val dispatchOutbox: RideDispatchOutboxRepository,
    private val rateLimiter: RedisRateLimiter,
) {

    @Transactional
    fun createRide(actor: AuthUser, request: CreateRideRequest, idempotencyKey: String? = null): CreateRideResponse {
        requireCustomer(actor)
        creationValidator.validate(request)

        val key = normalizeIdempotencyKey(idempotencyKey)
        val requestHash = key?.let { hashRequest(request) }
        customerRepository.findByIdForUpdate(actor.id).orElseThrow {
            NotFoundException(ErrorCode.USER_NOT_FOUND)
        }

        if (key != null && requestHash != null) {
            val previous = idempotencyRepository.find(actor.id, key)
            if (previous != null) {
                if (previous.requestHash != requestHash) {
                    throw ConflictException(message = "Idempotency-Key đã được dùng với nội dung khác")
                }
                val previousRide = rideRepository.findById(previous.rideId).orElseThrow {
                    NotFoundException(ErrorCode.RIDE_NOT_FOUND)
                }
                return RideResponseMapper.create(previousRide)
            }
        }

        if (rideRepository.existsByCustomerIdAndStatusIn(actor.id, ACTIVE_RIDE_STATUSES)) {
            throw ConflictException(
                ErrorCode.RIDE_ALREADY_ACTIVE,
                "Bạn đang có một chuyến chưa hoàn tất",
            )
        }

        val vehicle = findOwnedVehicle(request.vehicleId, actor.id)
        if (request.paymentMethod != PaymentMethod.CASH) {
            throw BadRequestException(
                message = "Hiện chỉ hỗ trợ tiền mặt; thanh toán ví/VNPay/MoMo chưa được bật",
            )
        }
        rateLimiter.check(RedisKeys.rideQuoteRate(actor.id.toString()), RIDE_QUOTE_LIMIT, RIDE_QUOTE_WINDOW)
        val quoteInput = RideQuoteInput(
            pickup = GeoPoint(request.pickupLat, request.pickupLng),
            dropoff = GeoPoint(request.dropoffLat, request.dropoffLng),
            pricingAt = request.scheduledAt ?: Instant.now(),
            vehicleType = vehicle.vehicleType,
        )
        quoteProvider.requirePricing(vehicle.vehicleType)
        val savedRoute = rideRoutes.resolve(actor.id, request.vehicleId, vehicle.vehicleType, quoteInput.pickup, quoteInput.dropoff, request.routeQuoteId)
        val quote = quoteProvider.quote(quoteInput.copy(route = savedRoute.drivingRoute()))
        val appliedVoucher = voucherService.calculate(actor, request.voucherCode, quote.estimatedFare)

        val pendingRide = rideRepository.saveAndFlush(
            RideFactory.create(
                request = request,
                customerId = actor.id,
                vehicle = vehicle,
                quote = quote,
                discountAmount = appliedVoucher?.discountAmount ?: 0,
            ),
        )
        if (key != null && requestHash != null) {
            idempotencyRepository.insert(actor.id, key, requestHash, pendingRide.id)
        }
        appliedVoucher?.let { voucherService.consume(actor.id, it, pendingRide.id) }
        stateMachine.recordCreated(pendingRide, actor.id)

        val ride = if (request.type == RideType.INSTANT) {
            val searchingRide = startSearching(
                ride = pendingRide,
            )
            dispatchOutbox.enqueue(searchingRide.id)
            searchingRide
        } else {
            pendingRide
        }

        return RideResponseMapper.create(ride)
    }

    @Transactional
    fun cancelRide(actor: AuthUser, rideId: Long, request: CancelRideRequest): RideStatusResponse {
        if (rideId <= 0) {
            throw BadRequestException(message = "rideId phải lớn hơn 0")
        }
        if (request.reason.isBlank() || request.reason.length > 255) {
            throw BadRequestException(message = "Lý do hủy chuyến phải có nội dung và không vượt quá 255 ký tự")
        }
        val reason = request.reason.trim()
        val currentRide = rideRepository.findById(rideId).orElseThrow {
            NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        }
        // CANCEL có nhiều trạng thái đầu vào; giữ giá trị trước khi state machine sửa entity.
        val previousStatus = currentRide.status
        val ride = stateMachine.transition(
            RideTransitionCommand(
                rideId = rideId,
                event = RideTransitionEvent.CANCEL,
                actor = RideActor(userId = actor.id, role = actor.role),
                note = reason,
                cancelReason = reason,
            ),
        )
        return RideResponseMapper.status(ride, previousStatus)
    }

    private fun requireCustomer(actor: AuthUser) {
        if (actor.role != Role.CUSTOMER) {
            throw ForbiddenException(message = "Chỉ khách hàng mới có thể tạo ride")
        }
    }

    private fun findOwnedVehicle(vehicleId: Long, customerId: Long): CustomerVehicleSnapshot =
        vehicleQueryPort.findOwnedVehicle(vehicleId, customerId)
            ?: throw NotFoundException(
                ErrorCode.VEHICLE_NOT_FOUND,
                "Không tìm thấy xe thuộc khách hàng hiện tại",
            )

    private fun startSearching(
        ride: Ride,
    ): Ride {
        val searchingRide = stateMachine.transition(
            RideTransitionCommand(
                rideId = ride.id,
                event = RideTransitionEvent.START_SEARCH,
                actor = customerRideActor(ride.customerId),
                note = "Bắt đầu tìm tài xế cho ride tức thời",
            ),
        )

        return searchingRide
    }

    private fun normalizeIdempotencyKey(raw: String?): String? {
        if (raw == null) return null
        val key = raw.trim()
        if (key.isEmpty() || key.length > 128) {
            throw BadRequestException(message = "Idempotency-Key phải có từ 1 đến 128 ký tự")
        }
        return key
    }

    private fun hashRequest(request: CreateRideRequest): String = MessageDigest.getInstance("SHA-256")
        .digest(request.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val RIDE_QUOTE_LIMIT = 30L
        val RIDE_QUOTE_WINDOW = java.time.Duration.ofMinutes(1)
    }
}
