package com.nhom15.datxebackend.ride.repository

import com.nhom15.datxebackend.common.port.ActiveRideQueryPort
import com.nhom15.datxebackend.common.port.ActiveRideSnapshot
import org.springframework.stereotype.Repository

@Repository
class RideTrackingQueryAdapter(
    private val rideRepository: RideRepository,
) : ActiveRideQueryPort {
    override fun findActiveRideForDriver(driverId: Long): ActiveRideSnapshot? =
        rideRepository.findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(
            driverId,
            ACTIVE_RIDE_STATUSES,
        )?.let { ride ->
            ActiveRideSnapshot(rideId = ride.id, customerId = ride.customerId)
        }
}
