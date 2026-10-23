package com.nhom15.datxebackend.ride.event

import com.nhom15.datxebackend.common.event.DomainEvent
import java.time.Instant
import java.util.UUID

data class PickupHandoverConfirmed(
    val rideId: Long,
    val driverId: Long,
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent
