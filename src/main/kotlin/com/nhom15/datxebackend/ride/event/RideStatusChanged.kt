package com.nhom15.datxebackend.ride.event

import com.nhom15.datxebackend.common.enums.ActorRole
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.event.DomainEvent
import java.time.Instant
import java.util.UUID

data class RideStatusChanged(
    val rideId: Long,
    val customerId: Long,
    val driverId: Long?,

    val from: RideStatus?,
    val to: RideStatus,

    val actor: ActorRole = ActorRole.SYSTEM,

    val reason: String? = null,

    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
