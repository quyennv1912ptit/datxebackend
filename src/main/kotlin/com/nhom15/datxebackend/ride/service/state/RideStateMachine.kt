package com.nhom15.datxebackend.ride.service.state

import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.chat.service.ConversationService
import com.nhom15.datxebackend.common.domain.Money
import com.nhom15.datxebackend.common.enums.ActorRole
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.event.EventPublisher
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.port.RideAssignmentResult
import com.nhom15.datxebackend.ride.entity.HandoverPhase
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideActorRole
import com.nhom15.datxebackend.ride.entity.RideStatusHistory
import com.nhom15.datxebackend.ride.entity.VehicleHandoverReport
import com.nhom15.datxebackend.ride.event.RideCompleted
import com.nhom15.datxebackend.ride.event.RideDriverAssigned
import com.nhom15.datxebackend.ride.event.RideStatusChanged
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.repository.RideStatusHistoryRepository
import com.nhom15.datxebackend.ride.repository.VehicleHandoverReportRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class RideStateMachine(
    private val rideRepository: RideRepository,
    private val historyRepository: RideStatusHistoryRepository,
    private val eventPublisher: EventPublisher,
    private val reportRepository: VehicleHandoverReportRepository,
    private val conversationService: ConversationService,
    private val matching: MatchingProperties,
    private val dispatchOutbox: RideDispatchOutboxRepository,
    private val dispatchOffers: DispatchOfferRepository,
    private val drivers: DriverRepository,
    private val wallets: com.nhom15.datxebackend.payment.service.WalletService,
    private val vouchers: com.nhom15.datxebackend.voucher.service.VoucherService,
) {

    private val assignedDriverEvents = setOf(
        RideTransitionEvent.DRIVER_START_ARRIVING,
        RideTransitionEvent.DRIVER_ARRIVED,
        RideTransitionEvent.PICKUP_HANDOVER_CREATED,
        RideTransitionEvent.START_TRIP,
        RideTransitionEvent.ARRIVE_DESTINATION,
        RideTransitionEvent.DROPOFF_HANDOVER_CREATED,
        RideTransitionEvent.COMPLETE,
    )

    private val systemEvents = setOf(
        RideTransitionEvent.NO_DRIVER_FOUND,
        RideTransitionEvent.EXPIRE,
        RideTransitionEvent.REDISPATCH,
        RideTransitionEvent.SYSTEM_FAILURE,
    )

    @Transactional
    fun transition(command: RideTransitionCommand): Ride {
        if (command.event == RideTransitionEvent.ASSIGN_DRIVER) {
            throw BadRequestException(
                message = "ASSIGN_DRIVER phải được xử lý qua tryAssignDriver",
            )
        }

        val ride = rideRepository.findByIdForUpdate(command.rideId).orElseThrow { NotFoundException(ErrorCode.RIDE_NOT_FOUND) }
        authorize(ride, command.event, command.actor)

        if (command.expectedSearchGeneration != null && ride.redispatchCount != command.expectedSearchGeneration) {
            throw ConflictException(ErrorCode.INVALID_RIDE_STATE, "Job thuộc vòng tìm tài xế cũ")
        }
        if (command.event == RideTransitionEvent.NO_DRIVER_FOUND &&
            (ride.searchExpiresAt == null || ride.searchExpiresAt!!.isAfter(Instant.now()))) {
            throw ConflictException(ErrorCode.INVALID_RIDE_STATE, "Chưa hết thời gian tìm tài xế")
        }
        if (command.event == RideTransitionEvent.COMPLETE && ride.status != RideStatus.VEHICLE_RETURNED)
            throw com.nhom15.datxebackend.payment.exception.InvalidTripStateException()
        val previousStatus = ride.status
        val oldDriver = ride.driverId
        val retryDispatch = (command.event == RideTransitionEvent.CANCEL && command.actor.role == Role.DRIVER ||
            command.event == RideTransitionEvent.REDISPATCH) && previousStatus in setOf(RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVING)
        val nextStatus = if (retryDispatch) {
            if (ride.redispatchCount < matching.maxRedispatch) RideStatus.SEARCHING else RideStatus.NO_DRIVER_FOUND
        } else RideTransitionRules.resolve(previousStatus, command.event)

        val cancellationReason = command.cancelReason?.trim()
        if (command.event == RideTransitionEvent.CANCEL) {
            val customerSearching = command.actor.role == Role.CUSTOMER && previousStatus == RideStatus.SEARCHING
            if (cancellationReason == null && command.actor.role == Role.CUSTOMER && !customerSearching) {
                throw ConflictException(ErrorCode.INVALID_RIDE_STATE, "Chuyến đã đổi trạng thái. Vui lòng cập nhật rồi thử lại.")
            }
            if ((cancellationReason == null && !customerSearching) ||
                (cancellationReason != null && (cancellationReason.isEmpty() || cancellationReason.length > 255))) {
                throw BadRequestException(message = "Lý do hủy chuyến phải có nội dung và không vượt quá 255 ký tự")
            }
        }

        validateHandover(ride.id, command.event)
        if (rideRepository.compareAndSetStatus(ride.id, previousStatus.name, nextStatus.name, ride.version) != 1) {
            throw ConflictException(ErrorCode.INVALID_RIDE_STATE)
        }
        if (nextStatus == RideStatus.SEARCHING) {
            ride.searchExpiresAt = Instant.now().plus(matching.searchTimeout)
            if (retryDispatch) {
                ride.redispatchCount++
                ride.lastRedispatchReason = command.cancelReason?.trim()?.takeIf { it.isNotEmpty() }
                    ?: command.note?.trim()?.takeIf { it.isNotEmpty() }
                ride.lastRedispatchAt = Instant.now()
                ride.lastRedispatchDriverId = oldDriver
                ride.driverId = null
                ride.acceptedAt = null
            }
        }

        if (command.event == RideTransitionEvent.CANCEL && !retryDispatch) {
            ride.cancelReason = cancellationReason
            ride.cancelledBy = command.actor.userId
            ride.cancelledByRole = command.actor.persistedRole?.name
            ride.cancelledAt = Instant.now()
        }

        if (nextStatus == RideStatus.IN_PROGRESS) ride.startedAt = Instant.now()
        if (nextStatus == RideStatus.COMPLETED) ride.completedAt = Instant.now()

        if (nextStatus == RideStatus.COMPLETED && ride.finalFare == null) {
            ride.finalFare = (ride.fareEstimate - ride.discountAmount).coerceAtLeast(0)
        }

        ride.status = nextStatus
        rideRepository.saveAndFlush(ride)
        if (nextStatus.terminal && nextStatus != RideStatus.COMPLETED) {
            // Chuyến không hoàn thành: trả lại lượt dùng voucher để khách dùng lại.
            vouchers.releaseForRide(ride.id)
        }
        if (nextStatus == RideStatus.SEARCHING) dispatchOutbox.schedule(ride.id)
        if (nextStatus.terminal || retryDispatch) {
            dispatchOffers.closeRide(ride.id)
            oldDriver?.let { driverId ->
                drivers.findByIdForUpdate(driverId).orElse(null)?.let { driver ->
                    if (driver.status == DriverStatus.BUSY) {
                        driver.status = DriverStatus.ONLINE
                        drivers.save(driver)
                    }
                }
            }
        }
        recordHistory(ride, previousStatus, nextStatus, command.actor.userId, command.note, command.actor.persistedRole)
        publishStatusChanged(
            ride = ride,
            from = previousStatus,
            to = nextStatus,
            actorRole = command.actor.persistedRole,
            reason = if (retryDispatch && nextStatus == RideStatus.SEARCHING) "Đang tìm tài xế khác" else command.cancelReason?.trim(),
            eventDriverId = if (retryDispatch) oldDriver else ride.driverId,
        )

        if (nextStatus == RideStatus.COMPLETED) {
            publishRideCompleted(ride)
        }

        return ride
    }

    @Transactional
    fun recordCreated(ride: Ride, actorId: Long) {
        recordHistory(
            ride = ride,
            from = null,
            to = RideStatus.PENDING,
            actorId = actorId,
            actorRole = RideActorRole.CUSTOMER,
            note = "Khởi tạo ride",
        )
        publishStatusChanged(
            ride = ride,
            from = null,
            to = RideStatus.PENDING,
            actorRole = RideActorRole.CUSTOMER,
        )
    }

    /**
     * Nhánh riêng cho Matching: câu UPDATE có điều kiện quyết định duy nhất ai thắng
     * khi nhiều tài xế nhận cùng một ride.
     */
    @Transactional
    fun tryAssignDriver(
        rideId: Long,
        driverId: Long,
        note: String? = null,
    ): RideAssignmentResult? {
        if (rideId <= 0 || driverId <= 0) {
            throw BadRequestException(message = "rideId và driverId phải lớn hơn 0")
        }

        val updatedRows = rideRepository.assignDriverIfSearching(rideId, driverId)
        if (updatedRows == 0) return null

        val ride = findRide(rideId)
        val driver = drivers.findByIdForUpdate(driverId).orElseThrow { NotFoundException(ErrorCode.USER_NOT_FOUND) }
        if (driver.status != DriverStatus.ONLINE) throw ConflictException(ErrorCode.DRIVER_NOT_AVAILABLE)
        wallets.requireEligibleDriver(driverId,
            (ride.fareEstimate - ride.discountAmount).coerceAtLeast(0).takeIf {
                ride.paymentMethod == com.nhom15.datxebackend.common.enums.PaymentMethod.CASH
            })
        driver.status = DriverStatus.BUSY
        drivers.save(driver)
        dispatchOffers.outstanding(rideId)?.let { dispatchOffers.close(it.offerId, "ACCEPTED") }
        conversationService.upsert(ride.id, ride.customerId, driverId)
        recordHistory(
            ride = ride,
            from = RideStatus.SEARCHING,
            to = RideStatus.DRIVER_ASSIGNED,
            actorId = driverId,
            actorRole = RideActorRole.DRIVER,
            note = note,
        )
        publishStatusChanged(
            ride = ride,
            from = RideStatus.SEARCHING,
            to = RideStatus.DRIVER_ASSIGNED,
            actorRole = RideActorRole.DRIVER,
        )
        eventPublisher.publish(
            RideDriverAssigned(rideId = ride.id, customerId = ride.customerId, driverId = driverId),
        )
        return RideAssignmentResult(
            rideId = ride.id,
            customerId = ride.customerId,
            driverId = driverId,
        )
    }

    /** Admin reassigns a no-driver ride or replaces a driver whose heartbeat expired. */
    @Transactional
    fun adminAssignDriver(rideId: Long, driverId: Long, adminId: Long): Pair<Ride, RideStatus> {
        if (rideId <= 0 || driverId <= 0 || adminId <= 0) {
            throw BadRequestException(message = "rideId, driverId và adminId phải lớn hơn 0")
        }
        val ride = rideRepository.findByIdForUpdate(rideId).orElseThrow {
            NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        }
        val previousStatus = ride.status
        if (previousStatus !in setOf(RideStatus.NO_DRIVER_FOUND, RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVING)) {
            throw ConflictException(
                ErrorCode.INVALID_RIDE_STATE,
                "Chỉ gán lại chuyến chưa tìm được tài xế hoặc tài xế chưa tới điểm đón mới mất liên lạc",
            )
        }

        if (rideRepository.compareAndSetStatus(ride.id, previousStatus.name, RideStatus.DRIVER_ASSIGNED.name, ride.version) != 1) {
            throw ConflictException(ErrorCode.INVALID_RIDE_STATE)
        }
        drivers.findByIdForUpdate(driverId).orElseThrow { NotFoundException(ErrorCode.USER_NOT_FOUND) }.let { driver ->
            wallets.requireEligibleDriver(driverId,
            (ride.fareEstimate - ride.discountAmount).coerceAtLeast(0).takeIf {
                ride.paymentMethod == com.nhom15.datxebackend.common.enums.PaymentMethod.CASH
            })
        driver.status = DriverStatus.BUSY
            drivers.save(driver)
        }
        ride.driverId = driverId
        ride.status = RideStatus.DRIVER_ASSIGNED
        ride.acceptedAt = Instant.now()
        rideRepository.saveAndFlush(ride)
        conversationService.upsert(ride.id, ride.customerId, driverId)
        recordHistory(
            ride = ride,
            from = previousStatus,
            to = RideStatus.DRIVER_ASSIGNED,
            actorId = adminId,
            note = "Admin gán tài xế $driverId",
            actorRole = null,
        )
        publishStatusChanged(
            ride = ride,
            from = previousStatus,
            to = RideStatus.DRIVER_ASSIGNED,
            actorRole = null,
        )
        eventPublisher.publish(RideDriverAssigned(rideId = ride.id, customerId = ride.customerId, driverId = driverId))
        return ride to previousStatus
    }

    // Dữ liệu và ảnh cần được kiểm tra tại service tạo/sửa biên bản trước khi lưu.
    private fun validateHandover(rideId: Long, event: RideTransitionEvent) {
        when (event) {
            RideTransitionEvent.PICKUP_HANDOVER_CREATED -> {
                requireReport(rideId, HandoverPhase.PICKUP)
            }

            RideTransitionEvent.START_TRIP -> {
                val pickup = requireReport(rideId, HandoverPhase.PICKUP)
                if (!pickup.confirmedByCustomer) {
                    throw BadRequestException(
                        ErrorCode.HANDOVER_REPORT_REQUIRED,
                        "Khách hàng chưa xác nhận biên bản nhận xe",
                    )
                }
            }

            RideTransitionEvent.DROPOFF_HANDOVER_CREATED -> {
                requireReport(rideId, HandoverPhase.DROPOFF)
            }

            RideTransitionEvent.COMPLETE -> {
                val report = requireReport(rideId, HandoverPhase.DROPOFF)
                if (!report.confirmedByCustomer) {
                    throw BadRequestException(
                        ErrorCode.HANDOVER_REPORT_REQUIRED,
                        "Khách hàng chưa xác nhận biên bản trả xe",
                    )
                }
            }

            else -> Unit
        }
    }

    private fun requireReport(rideId: Long, phase: HandoverPhase): VehicleHandoverReport {
        val report = reportRepository.findByRideIdAndPhase(rideId, phase)
        if (report == null) {
            throw BadRequestException(
                ErrorCode.HANDOVER_REPORT_REQUIRED,
                "Chưa có biên bản bàn giao $phase của chuyến này",
            )
        }
        return report
    }

    private fun findRide(rideId: Long): Ride =
        rideRepository.findById(rideId).orElseThrow {
            NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        }

    private fun authorize(ride: Ride, event: RideTransitionEvent, actor: RideActor) {
        if (actor.role == Role.ADMIN) return

        val isOwnerCustomer =
            actor.role == Role.CUSTOMER && actor.userId == ride.customerId

        val isAssignedDriver =
            actor.role == Role.DRIVER &&
                actor.userId != null &&
                actor.userId == ride.driverId

        val allowed = when {
            event == RideTransitionEvent.START_SEARCH ->
                actor.isSystem || isOwnerCustomer

            event == RideTransitionEvent.CANCEL ->
                actor.isSystem || isOwnerCustomer || isAssignedDriver

            event in assignedDriverEvents ->
                isAssignedDriver

            event in systemEvents ->
                actor.isSystem

            event == RideTransitionEvent.ASSIGN_DRIVER ->
                false

            else -> false
        }

        if (!allowed) {
            throw ForbiddenException(message = "Bạn không có quyền thay đổi trạng thái ride này")
        }
    }

    private fun recordHistory(
        ride: Ride,
        from: RideStatus?,
        to: RideStatus,
        actorId: Long?,
        note: String?,
        actorRole: RideActorRole?,
    ) {
        historyRepository.save(
            RideStatusHistory(
                ride = ride,
                fromStatus = from,
                toStatus = to,
                actorId = actorId,
                note = note,
                actorRole = actorRole,
            ),
        )
    }

    private fun publishStatusChanged(
        ride: Ride,
        from: RideStatus?,
        to: RideStatus,
        actorRole: RideActorRole?,
        reason: String? = null,
        eventDriverId: Long? = ride.driverId,
    ) {
        val eventActor = when (actorRole) {
            RideActorRole.CUSTOMER -> ActorRole.USER
            RideActorRole.DRIVER -> ActorRole.DRIVER
            RideActorRole.SYSTEM, null -> ActorRole.SYSTEM
        }

        eventPublisher.publish(
            RideStatusChanged(
                rideId = ride.id,
                customerId = ride.customerId,
                driverId = eventDriverId,
                from = from,
                to = to,
                actor = eventActor,
                reason = reason,
            ),
        )
    }

    private fun publishRideCompleted(ride: Ride) {
        val driverId = ride.driverId
            ?: throw ConflictException(ErrorCode.INVALID_RIDE_STATE, "Ride chưa có tài xế")
        val finalFare = ride.finalFare
            ?: throw ConflictException(ErrorCode.INVALID_RIDE_STATE, "Ride chưa có giá cuối cùng")

        eventPublisher.publish(
            RideCompleted(
                rideId = ride.id,
                customerId = ride.customerId,
                driverId = driverId,
                finalFare = Money(finalFare),
            ),
        )
    }
}
