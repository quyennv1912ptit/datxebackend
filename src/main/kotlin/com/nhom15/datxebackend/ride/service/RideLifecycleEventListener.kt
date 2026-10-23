package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.ride.event.RideNoDriverFound
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.ride.service.state.RideTransitionCommand
import com.nhom15.datxebackend.ride.service.state.RideTransitionEvent
import com.nhom15.datxebackend.ride.service.state.SYSTEM_RIDE_ACTOR
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class RideLifecycleEventListener(
    private val rideRepository: RideRepository,
    private val stateMachine: RideStateMachine,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    @Transactional
    fun onNoDriverFound(event: RideNoDriverFound) {
        val ride = rideRepository.findById(event.rideId).orElse(null) ?: return
        if (ride.status != RideStatus.SEARCHING) return

        try {
            stateMachine.transition(
                RideTransitionCommand(
                    rideId = event.rideId,
                    event = RideTransitionEvent.NO_DRIVER_FOUND,
                    actor = SYSTEM_RIDE_ACTOR,
                    note = "Không còn tài xế phù hợp để nhận chuyến",
                ),
            )
        } catch (exception: ConflictException) {
            // A concurrent accept/cancel has already moved the ride out of SEARCHING.
            log.debug("Bỏ qua RideNoDriverFound cũ cho ride {}: {}", event.rideId, exception.message)
        } catch (exception: OptimisticLockingFailureException) {
            log.debug("Bỏ qua RideNoDriverFound do ride {} vừa được cập nhật đồng thời", event.rideId)
        }
    }
}
