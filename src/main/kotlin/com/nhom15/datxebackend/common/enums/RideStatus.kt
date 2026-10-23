package com.nhom15.datxebackend.common.enums

enum class RideStatus(val terminal: Boolean = false) {
    PENDING, SEARCHING, DRIVER_ASSIGNED, DRIVER_ARRIVING, DRIVER_ARRIVED,
    VEHICLE_HANDOVER, IN_PROGRESS, ARRIVED_DESTINATION, VEHICLE_RETURNED,
    COMPLETED(true), CANCELLED(true), NO_DRIVER_FOUND(true), EXPIRED(true);
}