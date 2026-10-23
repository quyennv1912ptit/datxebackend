package com.nhom15.datxebackend.ride.dto.response

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.ride.entity.RideActorRole
import java.time.Instant

data class RideStatusHistoryResponse(
    val id: Long,
    val fromStatus: RideStatus?,
    val toStatus: RideStatus,
    val actorId: Long?,
    val note: String?,
    val createdAt: Instant,
    val actorRole: RideActorRole? = null,
)
