package com.nhom15.datxebackend.tracking.websocket

import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.ws.StompPrincipal
import com.nhom15.datxebackend.common.port.ActiveRideQueryPort
import com.nhom15.datxebackend.tracking.dto.DriverLocation
import com.nhom15.datxebackend.tracking.service.LocationService
import org.springframework.messaging.MessagingException
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import java.security.Principal

/** Handles authenticated driver location updates over STOMP. */
@Controller
class CoordinateController(
    private val messagingTemplate: SimpMessagingTemplate,
    private val locationService: LocationService,
    private val activeRideQueryPort: ActiveRideQueryPort,
) {

    @MessageMapping("/location")
    fun updateDriverLocation(
        @Payload currentLocation: DriverLocation,
        principal: Principal,
    ) {
        val driver = (principal as? StompPrincipal)?.recipient as? Recipient.Driver
            ?: throw MessagingException("Chỉ tài xế đã xác thực mới được gửi vị trí")

        locationService.saveDriverLocation(driver.id, currentLocation.location)

        val activeRide = activeRideQueryPort.findActiveRideForDriver(driver.id) ?: return

        messagingTemplate.convertAndSendToUser(
            Recipient.Customer(activeRide.customerId).key,
            "/queue/rides/${activeRide.rideId}/location",
            currentLocation,
        )
    }
}
