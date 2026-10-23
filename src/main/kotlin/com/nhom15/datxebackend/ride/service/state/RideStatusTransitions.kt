package com.nhom15.datxebackend.ride.service.state

import com.nhom15.datxebackend.common.enums.RideStatus

private val TRANSITIONS: Map<RideStatus, Set<RideStatus>> = mapOf(
    RideStatus.PENDING to setOf(
        RideStatus.SEARCHING, RideStatus.CANCELLED, RideStatus.EXPIRED
    ),
    RideStatus.SEARCHING to setOf(
        RideStatus.DRIVER_ASSIGNED, RideStatus.NO_DRIVER_FOUND, RideStatus.CANCELLED
    ),
    RideStatus.DRIVER_ASSIGNED to setOf(
        RideStatus.DRIVER_ARRIVING, RideStatus.CANCELLED
    ),
    RideStatus.DRIVER_ARRIVING to setOf(
        RideStatus.DRIVER_ARRIVED, RideStatus.CANCELLED
    ),
    RideStatus.DRIVER_ARRIVED to setOf(
        RideStatus.VEHICLE_HANDOVER, RideStatus.CANCELLED
    ),
    RideStatus.VEHICLE_HANDOVER to setOf(
        RideStatus.IN_PROGRESS, RideStatus.CANCELLED
    ),
    RideStatus.IN_PROGRESS to setOf(RideStatus.ARRIVED_DESTINATION),
    RideStatus.ARRIVED_DESTINATION to setOf(RideStatus.VEHICLE_RETURNED),
    RideStatus.VEHICLE_RETURNED to setOf(RideStatus.COMPLETED),
    RideStatus.NO_DRIVER_FOUND to setOf(RideStatus.CANCELLED),
)

internal fun RideStatus.canTransitionTo(next: RideStatus): Boolean =
    TRANSITIONS[this]?.contains(next) == true
