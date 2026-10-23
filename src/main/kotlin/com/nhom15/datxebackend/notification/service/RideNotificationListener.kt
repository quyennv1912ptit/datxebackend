package com.nhom15.datxebackend.notification.service

import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.enums.ActorRole
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.*
import com.nhom15.datxebackend.ride.event.RideStatusChanged
import com.nhom15.datxebackend.ride.event.PickupHandoverConfirmed
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Service
class RideNotificationListener(
    private val notificationPort: NotificationPort,
    private val driverRepository: DriverRepository,
    private val rideRepository: RideRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onRideStatusChanged(event: RideStatusChanged) {
        when (event.to) {
            RideStatus.DRIVER_ASSIGNED -> notifyDriverAssigned(event)

            RideStatus.DRIVER_ARRIVING,
            RideStatus.DRIVER_ARRIVED,
            RideStatus.VEHICLE_HANDOVER,
            RideStatus.IN_PROGRESS,
            RideStatus.ARRIVED_DESTINATION,
            RideStatus.VEHICLE_RETURNED -> notifyRideUpdated(event)

            RideStatus.COMPLETED -> notifyRideCompleted(event)
            RideStatus.CANCELLED -> notifyRideCancelled(event)
            RideStatus.NO_DRIVER_FOUND, RideStatus.EXPIRED -> notifyNoDriver(event)
            RideStatus.PENDING, RideStatus.SEARCHING -> Unit
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onPickupHandoverConfirmed(event: PickupHandoverConfirmed) {
        notificationPort.send(
            Recipient.Driver(event.driverId),
            HandoverConfirmedPayload(event.rideId),
        )
    }

    private fun notifyDriverAssigned(event: RideStatusChanged) {
        val driverId = event.driverId ?: return
        val driver = driverRepository.findByIdOrNull(driverId) ?: run {
            log.warn("Không tìm thấy tài xế {} khi báo chuyến {}", driverId, event.rideId)
            return
        }

        val payload = RideAssignedPayload(
            rideId = event.rideId,
            driver = DriverBrief(
                id = driverId,
                name = driver.fullName,
                phone = driver.phoneNumber,
                rating = driver.averageRating ?: 5.0,
                avatarUrl = driver.avatarUrl
            )
        )
        notificationPort.send(Recipient.Customer(event.customerId), payload)
    }

    private fun notifyRideUpdated(event: RideStatusChanged) {
        val payload = RideUpdatedPayload(event.rideId, event.to)
        if (event.actor != ActorRole.USER) {
            notificationPort.send(Recipient.Customer(event.customerId), payload)
        }
        if (event.actor != ActorRole.DRIVER) {
            event.driverId?.let { notificationPort.send(Recipient.Driver(it), payload) }
        }
    }

    private fun notifyRideCompleted(event: RideStatusChanged) {
        val ride = rideRepository.findByIdOrNull(event.rideId) ?: run {
            log.warn("Không tìm thấy chuyến với id {}", event.rideId)
            return
        }

        val payload = RideCompletedPayload(event.rideId, ride.finalFare ?: 0, ride.distanceKm)
        notificationPort.send(Recipient.Customer(event.customerId), payload)
    }

    private fun notifyRideCancelled(event: RideStatusChanged) {
        val payload = RideCancelledPayload(event.rideId, event.reason)

        if (event.actor != ActorRole.USER) {
            notificationPort.send(Recipient.Customer(event.customerId), payload)
        }
        if (event.actor != ActorRole.DRIVER) {
            event.driverId?.let { notificationPort.send(Recipient.Driver(it), payload) }
        }
    }

    private fun notifyNoDriver(event: RideStatusChanged) {
        val payload = RideNoDriverPayload(event.rideId, event.to)
        notificationPort.send(Recipient.Customer(event.customerId), payload)

        if (event.to == RideStatus.EXPIRED) {
            event.driverId?.let { notificationPort.send(Recipient.Driver(it), payload) }
        }
    }
}
