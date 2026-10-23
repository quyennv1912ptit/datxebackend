package com.nhom15.datxebackend.common.port

interface ActiveRideQueryPort {
    fun findActiveRideForDriver(driverId: Long): ActiveRideSnapshot?
}

data class ActiveRideSnapshot(
    val rideId: Long,
    val customerId: Long,
)
