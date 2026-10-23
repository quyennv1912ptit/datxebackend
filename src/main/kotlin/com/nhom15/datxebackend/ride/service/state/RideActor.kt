package com.nhom15.datxebackend.ride.service.state

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.ride.entity.RideActorRole

/** Người hoặc tiến trình hệ thống thực hiện thay đổi trạng thái. */
data class RideActor(
    val userId: Long?,
    val role: Role?,
) {
    val isSystem: Boolean
        get() = userId == null && role == null

    // ADMIN is not a permitted persisted role in the supplied schema.
    val persistedRole: RideActorRole?
        get() = when {
            isSystem -> RideActorRole.SYSTEM
            role == Role.CUSTOMER -> RideActorRole.CUSTOMER
            role == Role.DRIVER -> RideActorRole.DRIVER
            else -> null
        }
}

val SYSTEM_RIDE_ACTOR = RideActor(userId = null, role = null)

fun customerRideActor(userId: Long) = RideActor(userId, Role.CUSTOMER)

fun driverRideActor(driverId: Long) = RideActor(driverId, Role.DRIVER)

fun adminRideActor(userId: Long) = RideActor(userId, Role.ADMIN)
