package com.nhom15.datxebackend.tracking.dto

import com.nhom15.datxebackend.common.domain.GeoPoint
import java.time.Instant

data class DriverLocation(
    val location: GeoPoint,
    val timestamp: Instant,
    val accuracy: Double? = null,
    val receivedAt: Instant? = null,
)

object DriverLocationPolicy {
    /** Coarse fixes are kept for display, but never make a driver dispatchable. */
    const val MAX_DISPATCH_ACCURACY_METERS = 100.0

    /** A phone clock may run slightly ahead; 30s used to block newer samples for half a minute. */
    const val MAX_FUTURE_SKEW_SECONDS = 3L
    private const val MAX_STORAGE_ACCURACY_METERS = 10_000.0
    private const val MAX_SAMPLE_AGE_SECONDS = 120L

    fun valid(update: DriverLocation, now: Instant): Boolean =
        update.location.lat.isFinite() && update.location.lat in -85.05112878..85.05112878 &&
        update.location.lng.isFinite() && update.location.lng in -180.0..180.0 &&
        (update.accuracy == null || (update.accuracy.isFinite() &&
            update.accuracy in 0.0..MAX_STORAGE_ACCURACY_METERS)) &&
        !update.timestamp.isAfter(now.plusSeconds(MAX_FUTURE_SKEW_SECONDS)) &&
        !update.timestamp.isBefore(now.minusSeconds(MAX_SAMPLE_AGE_SECONDS))

    /** Null accuracy means a legacy client that did not report it; keep those samples dispatchable. */
    fun dispatchEligible(accuracy: Double?): Boolean =
        accuracy == null || (accuracy.isFinite() && accuracy in 0.0..MAX_DISPATCH_ACCURACY_METERS)

    /** Reads the accuracy persisted in the driver's Redis location hash. */
    fun dispatchEligible(rawAccuracy: String?): Boolean {
        if (rawAccuracy.isNullOrBlank()) return true
        val value = rawAccuracy.toDoubleOrNull() ?: return false
        return dispatchEligible(value)
    }
}
