package com.nhom15.datxebackend.notification

import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.event.DomainEvent
import com.nhom15.datxebackend.common.event.EventPublisher
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.notification.api.DeliveryResult
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.HandoverConfirmedPayload
import com.nhom15.datxebackend.notification.dto.NotificationPayload
import com.nhom15.datxebackend.notification.service.RideNotificationListener
import com.nhom15.datxebackend.ride.dto.request.ConfirmHandoverRequest
import com.nhom15.datxebackend.ride.entity.HandoverPhase
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.entity.VehicleHandoverReport
import com.nhom15.datxebackend.ride.event.DropoffHandoverConfirmed
import com.nhom15.datxebackend.ride.event.PickupHandoverConfirmed
import com.nhom15.datxebackend.ride.repository.HandoverPhotoRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.repository.VehicleHandoverReportRepository
import com.nhom15.datxebackend.ride.service.RideHandoverService
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.Optional

/**
 * Khách xác nhận biên bản trả xe phải báo realtime cho tài xế, giống luồng xác nhận
 * biên bản nhận xe, để tài xế biết có thể hoàn tất chuyến.
 */
class HandoverNotificationTests {
    private val rideId = 42L
    private val customerId = 11L
    private val driverId = 7L

    private val notifications = RecordingNotificationPort()
    private val listener = RideNotificationListener(
        notifications,
        Mockito.mock(DriverRepository::class.java),
        Mockito.mock(RideRepository::class.java),
        Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate::class.java),
    )

    @Test
    fun `xac nhan tra xe day HANDOVER_CONFIRMED kem phase DROPOFF cho tai xe`() {
        listener.onDropoffHandoverConfirmed(DropoffHandoverConfirmed(rideId = rideId, driverId = driverId))

        val (to, payload) = notifications.single()
        assertEquals(Recipient.Driver(driverId), to)
        assertEquals(PayloadType.HANDOVER_CONFIRMED, payload.type)
        val handover = payload as HandoverConfirmedPayload
        assertEquals(rideId, handover.rideId)
        assertEquals(HandoverPhase.DROPOFF, handover.phase)
        assertTrue(handover.confirmedByCustomer)
    }

    @Test
    fun `xac nhan nhan xe van giu phase PICKUP`() {
        listener.onPickupHandoverConfirmed(PickupHandoverConfirmed(rideId = rideId, driverId = driverId))

        val (to, payload) = notifications.single()
        assertEquals(Recipient.Driver(driverId), to)
        assertEquals(HandoverPhase.PICKUP, (payload as HandoverConfirmedPayload).phase)
    }

    @Test
    fun `confirmDropoff chi phat su kien khi xac nhan thuc su doi trang thai`() {
        val ride = ride()
        val report = VehicleHandoverReport(ride, HandoverPhase.DROPOFF, odometer = 15_000)
        val rideRepository = Mockito.mock(RideRepository::class.java)
        val reportRepository = Mockito.mock(VehicleHandoverReportRepository::class.java)
        val photoRepository = Mockito.mock(HandoverPhotoRepository::class.java)
        val eventPublisher = RecordingEventPublisher()
        val service = RideHandoverService(
            rideRepository,
            reportRepository,
            photoRepository,
            Mockito.mock(RideStateMachine::class.java),
            eventPublisher,
            Mockito.mock(CloudinaryAssetService::class.java),
        )
        Mockito.`when`(rideRepository.findByIdForUpdate(rideId)).thenReturn(Optional.of(ride))
        Mockito.`when`(reportRepository.findByRideIdAndPhase(rideId, HandoverPhase.DROPOFF)).thenReturn(report)
        Mockito.`when`(photoRepository.findAllByReportIdOrderByCreatedAtAsc(report.id)).thenReturn(emptyList())

        val customer = AuthUser(customerId, "customer-uid", Role.CUSTOMER, "Khách", null, null, "ACTIVE")
        service.confirmDropoff(customer, rideId, ConfirmHandoverRequest(confirmed = true))
        // Khách bấm xác nhận lần nữa (hoặc client retry) không được dội thông báo.
        service.confirmDropoff(customer, rideId, ConfirmHandoverRequest(confirmed = true))

        assertEquals(1, eventPublisher.events.size)
        val event = eventPublisher.events.single() as DropoffHandoverConfirmed
        assertEquals(rideId, event.rideId)
        assertEquals(driverId, event.driverId)
        assertTrue(report.confirmedByCustomer)
    }

    private class RecordingNotificationPort : NotificationPort {
        private val sent = mutableListOf<Pair<Recipient, NotificationPayload>>()

        override fun send(to: Recipient, payload: NotificationPayload): DeliveryResult {
            sent += to to payload
            return DeliveryResult.SENT_WS
        }

        fun single(): Pair<Recipient, NotificationPayload> {
            assertEquals(1, sent.size, "phải gửi đúng một thông báo")
            return sent.single()
        }
    }

    private class RecordingEventPublisher : EventPublisher {
        val events = mutableListOf<DomainEvent>()

        override fun publish(event: DomainEvent) {
            events += event
        }
    }

    private fun ride() = Ride(
        customerId = customerId,
        driverId = driverId,
        vehicleId = 9,
        status = RideStatus.VEHICLE_RETURNED,
        type = RideType.INSTANT,
        pickup = Location(),
        dropoff = Location(),
        vehicleType = VehicleType.CAR,
        transmission = Transmission.AUTO,
        fareEstimate = 100_000,
        paymentMethod = PaymentMethod.CASH,
    ).apply { id = rideId }
}
