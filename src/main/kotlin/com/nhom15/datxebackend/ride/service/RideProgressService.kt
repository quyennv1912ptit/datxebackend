package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.ride.dto.request.ArriveDestinationRequest
import com.nhom15.datxebackend.ride.dto.request.CompleteRideRequest
import com.nhom15.datxebackend.ride.dto.request.DriverArrivedRequest
import com.nhom15.datxebackend.ride.dto.request.DriverStartArrivingRequest
import com.nhom15.datxebackend.ride.dto.request.StartTripRequest
import com.nhom15.datxebackend.ride.dto.response.RideStatusResponse
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.ride.service.state.RideTransitionCommand
import com.nhom15.datxebackend.ride.service.state.RideTransitionEvent
import com.nhom15.datxebackend.ride.service.state.driverRideActor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RideProgressService(
    private val stateMachine: RideStateMachine,
) {
    @Transactional
    fun startArriving(actor: AuthUser, rideId: Long, request: DriverStartArrivingRequest): RideStatusResponse =
        transitionDriver(
            actor = actor,
            rideId = rideId,
            note = request.note,
            event = RideTransitionEvent.DRIVER_START_ARRIVING,
            previousStatus = RideStatus.DRIVER_ASSIGNED,
            forbiddenMessage = "Chỉ tài xế được gán mới có thể bắt đầu đi đến điểm đón",
            noteErrorMessage = "Ghi chú bắt đầu đi đón không được vượt quá 1000 ký tự",
        )

    @Transactional
    fun arrivePickup(actor: AuthUser, rideId: Long, request: DriverArrivedRequest): RideStatusResponse =
        transitionDriver(
            actor = actor,
            rideId = rideId,
            note = request.note,
            event = RideTransitionEvent.DRIVER_ARRIVED,
            previousStatus = RideStatus.DRIVER_ARRIVING,
            forbiddenMessage = "Chỉ tài xế được gán mới có thể báo đến điểm đón",
            noteErrorMessage = "Ghi chú đến điểm đón không được vượt quá 1000 ký tự",
        )

    @Transactional
    fun startTrip(actor: AuthUser, rideId: Long, request: StartTripRequest): RideStatusResponse =
        transitionDriver(
            actor = actor,
            rideId = rideId,
            note = request.note,
            event = RideTransitionEvent.START_TRIP,
            previousStatus = RideStatus.VEHICLE_HANDOVER,
            forbiddenMessage = "Chỉ tài xế được gán mới có thể bắt đầu chuyến",
            noteErrorMessage = "Ghi chú bắt đầu chuyến không được vượt quá 1000 ký tự",
        )

    @Transactional
    fun arriveDestination(actor: AuthUser, rideId: Long, request: ArriveDestinationRequest): RideStatusResponse =
        transitionDriver(
            actor = actor,
            rideId = rideId,
            note = request.note,
            event = RideTransitionEvent.ARRIVE_DESTINATION,
            previousStatus = RideStatus.IN_PROGRESS,
            forbiddenMessage = "Chỉ tài xế được gán mới có thể báo đến điểm trả",
            noteErrorMessage = "Ghi chú đến điểm trả không được vượt quá 1000 ký tự",
        )

    @Transactional
    fun completeRide(actor: AuthUser, rideId: Long, request: CompleteRideRequest): RideStatusResponse =
        transitionDriver(
            actor = actor,
            rideId = rideId,
            note = request.note,
            event = RideTransitionEvent.COMPLETE,
            previousStatus = RideStatus.VEHICLE_RETURNED,
            forbiddenMessage = "Chỉ tài xế được gán mới có thể hoàn tất chuyến",
            noteErrorMessage = "Ghi chú hoàn tất chuyến không được vượt quá 1000 ký tự",
        )

    private fun transitionDriver(
        actor: AuthUser,
        rideId: Long,
        note: String?,
        event: RideTransitionEvent,
        previousStatus: RideStatus,
        forbiddenMessage: String,
        noteErrorMessage: String,
    ): RideStatusResponse {
        if (actor.role != Role.DRIVER) {
            throw ForbiddenException(message = forbiddenMessage)
        }
        if (rideId <= 0) {
            throw BadRequestException(message = "rideId phải lớn hơn 0")
        }
        if (note != null && note.length > 1_000) {
            throw BadRequestException(message = noteErrorMessage)
        }
        val ride = stateMachine.transition(
            RideTransitionCommand(
                rideId = rideId,
                event = event,
                actor = driverRideActor(actor.id),
                note = note,
            ),
        )
        return RideResponseMapper.status(ride, previousStatus)
    }
}
