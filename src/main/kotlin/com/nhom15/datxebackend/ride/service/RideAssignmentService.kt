package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.port.RideAssignmentPort
import com.nhom15.datxebackend.common.port.RideAssignmentResult
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import org.springframework.stereotype.Service

@Service
class RideAssignmentService(
    private val rideStateMachine: RideStateMachine,
) : RideAssignmentPort {

    override fun assignDriver(rideId: Long, driverId: Long): RideAssignmentResult? =
        rideStateMachine.tryAssignDriver(rideId, driverId)
}
