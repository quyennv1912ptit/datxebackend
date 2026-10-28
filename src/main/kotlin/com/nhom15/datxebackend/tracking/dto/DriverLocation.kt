package com.nhom15.datxebackend.tracking.dto

import com.nhom15.datxebackend.common.domain.GeoPoint
import java.time.Instant

data class DriverLocation(
    val location: GeoPoint,
    val timestamp: Instant = Instant.now(),
    val accuracy: Double? = null,
    val receivedAt: Instant? = null,
)

object DriverLocationPolicy {
    fun valid(update: DriverLocation, now: Instant): Boolean =
        update.location.lat.isFinite() && update.location.lat in -85.05112878..85.05112878 &&
        update.location.lng.isFinite() && update.location.lng in -180.0..180.0 &&
        (update.accuracy == null || (update.accuracy.isFinite() && update.accuracy in 0.0..10000.0)) &&
        !update.timestamp.isAfter(now.plusSeconds(30)) && !update.timestamp.isBefore(now.minusSeconds(120))
}
