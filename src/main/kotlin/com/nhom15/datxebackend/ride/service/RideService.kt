package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.exception.AppException
import java.time.Duration
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.util.roundToNearestThousandVnd
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
    private val rateLimiter: RedisRateLimiter,
    private val wallets: com.nhom15.datxebackend.payment.service.WalletService,
) {

    @Transactional
    fun createRide(actor: AuthUser, request: CreateRideRequest, idempotencyKey: String? = null): CreateRideResponse {
        requireCustomer(actor)
        val key = normalizeIdempotencyKey(idempotencyKey)
        val requestHash = key?.let { hashRequest(request) }
        customerRepository.findByIdForUpdate(actor.id).orElseThrow {
            com.nhom15.datxebackend.payment.exception.UserNotFoundException()
        }

        if (key != null && requestHash != null) {
            val previous = idempotencyRepository.find(actor.id, key)
            if (previous != null) {
                if (previous.requestHash != requestHash) {
                    throw ConflictException(message = "Idempotency-Key Ä‘Ã£ Ä‘Æ°á»£c dÃ¹ng vá»›i ná»™i dung khÃ¡c")
                }
                val previousRide = rideRepository.findById(previous.rideId).orElseThrow {
                    NotFoundException(ErrorCode.RIDE_NOT_FOUND)
                }
                return RideResponseMapper.create(previousRide)
            }
        }

        creationValidator.validate(request)
        if (request.type == RideType.SCHEDULED &&
            rideRepository.existsByCustomerIdAndTypeAndStatus(actor.id, RideType.SCHEDULED, RideStatus.PENDING)
        ) {
            throw AppException(ErrorCode.SCHEDULED_RIDE_EXISTS)
        }

        rideRepository.findFirstByCustomerIdAndStatusInOrderByCreatedAtDesc(actor.id, ACTIVE_RIDE_STATUSES)?.let { active ->
            throw AppException(ErrorCode.ACTIVE_RIDE_EXISTS,
                details = mapOf("rideId" to active.id.toString()))
        }

        val vehicle = findOwnedVehicle(request.vehicleId, actor.id)
        if (request.paymentMethod !in setOf(PaymentMethod.CASH, PaymentMethod.WALLET)) {
            throw BadRequestException(
                message = "Chá»‰ há»— trá»£ thanh toÃ¡n CASH hoáº·c WALLET",
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
        val appliedVoucher = voucherService.calculate(actor, request.voucherCode, quote.estimatedFare, request.paymentMethod, request.voucherId)
        // Sá»‘ tiá»n khÃ¡ch thá»±c tráº£ Ä‘Æ°á»£c lÃ m trÃ²n 1.000; fare_estimate váº«n giá»¯ giÃ¡ gá»‘c Ä‘á»ƒ Ä‘á»‘i soÃ¡t.
        val payableFare = (quote.estimatedFare - (appliedVoucher?.discountAmount ?: 0))
            .coerceAtLeast(0)
            .roundToNearestThousandVnd()

        if (request.paymentMethod == PaymentMethod.WALLET) {
            wallets.checkBooking(actor.id, payableFare)
        }
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
        appliedVoucher?.let { voucherService.consume(actor.id, it, pendingRide.id, request.paymentMethod) }
        stateMachine.recordCreated(pendingRide, actor.id)

        val ride = if (request.type == RideType.INSTANT) {
            startSearching(pendingRide)
        } else {
            pendingRide
        }

        return RideResponseMapper.create(ride)
    }

    @Transactional
    fun cancelRide(actor: AuthUser, rideId: Long, request: CancelRideRequest): RideStatusResponse {
        if (rideId <= 0) {
            throw BadRequestException(message = "rideId pháº£i lá»›n hÆ¡n 0")
        }
        if (request.reason != null && (request.reason.isBlank() || request.reason.length > 255)) {
            throw BadRequestException(message = "LÃ½ do há»§y chuyáº¿n pháº£i cÃ³ ná»™i dung vÃ  khÃ´ng vÆ°á»£t quÃ¡ 255 kÃ½ tá»±")
        }
        val reason = request.reason?.trim()
        val currentRide = rideRepository.findById(rideId).orElseThrow {
            NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        }
        // CANCEL cÃ³ nhiá»u tráº¡ng thÃ¡i Ä‘áº§u vÃ o; giá»¯ giÃ¡ trá»‹ trÆ°á»›c khi state machine sá»­a entity.
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
            throw ForbiddenException(message = "Chá»‰ khÃ¡ch hÃ ng má»›i cÃ³ thá»ƒ táº¡o ride")
        }
    }

    private fun findOwnedVehicle(vehicleId: Long, customerId: Long): CustomerVehicleSnapshot =
        vehicleQueryPort.findOwnedVehicle(vehicleId, customerId)
            ?: throw NotFoundException(
                ErrorCode.VEHICLE_NOT_FOUND,
                "KhÃ´ng tÃ¬m tháº¥y xe thuá»™c khÃ¡ch hÃ ng hiá»‡n táº¡i",
            )

    private fun startSearching(
        ride: Ride,
    ): Ride {
        val searchingRide = stateMachine.transition(
            RideTransitionCommand(
                rideId = ride.id,
                event = RideTransitionEvent.START_SEARCH,
                actor = customerRideActor(ride.customerId),
                note = "Bắt đầu tìm tài xế cho chuyến đi tức thì",
            ),
        )

        return searchingRide
    }

    private fun normalizeIdempotencyKey(raw: String?): String? {
        if (raw == null) return null
        val key = raw.trim()
        if (key.isEmpty() || key.length > 128) {
            throw BadRequestException(message = "Idempotency-Key pháº£i cÃ³ tá»« 1 Ä‘áº¿n 128 kÃ½ tá»±")
        }
        return key
    }

    private fun hashRequest(request: CreateRideRequest): String = MessageDigest.getInstance("SHA-256")
        .digest(request.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val RIDE_QUOTE_LIMIT = 30L
        val RIDE_QUOTE_WINDOW = Duration.ofMinutes(1)
    }
}
