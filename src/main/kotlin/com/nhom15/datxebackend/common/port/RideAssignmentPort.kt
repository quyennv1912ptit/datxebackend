package com.nhom15.datxebackend.common.port

interface RideAssignmentPort {
    fun assignDriver(rideId: Long, driverId: Long): RideAssignmentResult?
}

data class RideAssignmentResult(
    val rideId: Long,
    val customerId: Long,
    val driverId: Long,
)
