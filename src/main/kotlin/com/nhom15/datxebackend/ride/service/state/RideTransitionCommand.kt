package com.nhom15.datxebackend.ride.service.state

data class RideTransitionCommand(
    val rideId: Long,
    val event: RideTransitionEvent,
    val actor: RideActor,
    val note: String? = null,
    val cancelReason: String? = null,
)
