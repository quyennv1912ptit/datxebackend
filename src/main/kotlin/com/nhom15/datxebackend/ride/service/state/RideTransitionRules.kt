package com.nhom15.datxebackend.ride.service.state

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.exception.ConflictException

internal object RideTransitionRules {

    private val transitions = mapOf(
        RideTransitionEvent.START_SEARCH to RideStatus.SEARCHING,
        RideTransitionEvent.ASSIGN_DRIVER to RideStatus.DRIVER_ASSIGNED,
        RideTransitionEvent.DRIVER_START_ARRIVING to RideStatus.DRIVER_ARRIVING,
        RideTransitionEvent.DRIVER_ARRIVED to RideStatus.DRIVER_ARRIVED,
        RideTransitionEvent.PICKUP_HANDOVER_CREATED to RideStatus.VEHICLE_HANDOVER,
        RideTransitionEvent.START_TRIP to RideStatus.IN_PROGRESS,
        RideTransitionEvent.ARRIVE_DESTINATION to RideStatus.ARRIVED_DESTINATION,
        RideTransitionEvent.DROPOFF_HANDOVER_CREATED to RideStatus.VEHICLE_RETURNED,
        RideTransitionEvent.COMPLETE to RideStatus.COMPLETED,
        RideTransitionEvent.NO_DRIVER_FOUND to RideStatus.NO_DRIVER_FOUND,
        RideTransitionEvent.EXPIRE to RideStatus.EXPIRED,
        RideTransitionEvent.CANCEL to RideStatus.CANCELLED,
    )

    fun resolve(current: RideStatus, event: RideTransitionEvent): RideStatus {
        return transitions[event]?.takeIf { current.canTransitionTo(it) }
            ?: throw ConflictException(
                ErrorCode.INVALID_RIDE_STATE,
                "Không thể thực hiện $event khi ride đang ở trạng thái $current",
            )
    }
}
