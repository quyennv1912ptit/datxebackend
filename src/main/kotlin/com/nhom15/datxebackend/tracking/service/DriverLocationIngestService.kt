package com.nhom15.datxebackend.tracking.service

import com.nhom15.datxebackend.common.port.ActiveRideQueryPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.tracking.dto.DriverLocation
import com.nhom15.datxebackend.tracking.dto.DriverLocationPolicy
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * One entry point for driver GPS samples, shared by STOMP and the REST fallback.
 */
@Service
class DriverLocationIngestService(
    private val locationService: LocationService,
    private val activeRideQueryPort: ActiveRideQueryPort,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    /** False when the sample is malformed or too old; callers may drop it silently. */
    fun ingest(driverId: Long, update: DriverLocation): Boolean {
        val now = Instant.now()
        if (!DriverLocationPolicy.valid(update, now)) return false
        if (!locationService.saveDriverLocation(driverId, update.location, update.timestamp)) return false
        activeRideQueryPort.findActiveRideForDriver(driverId)?.let { ride ->
            messagingTemplate.convertAndSendToUser(
                Recipient.Customer(ride.customerId).key,
                "/queue/rides/${ride.rideId}/location",
                update.copy(receivedAt = now),
            )
        }
        return true
    }
}
