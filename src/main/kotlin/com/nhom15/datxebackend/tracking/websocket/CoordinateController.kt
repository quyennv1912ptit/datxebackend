package com.nhom15.datxebackend.tracking.websocket

import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.ws.StompPrincipal
import com.nhom15.datxebackend.tracking.dto.DriverLocation
import com.nhom15.datxebackend.tracking.service.DriverLocationIngestService
import org.springframework.messaging.MessagingException
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Controller
import java.security.Principal

/** Handles authenticated driver location updates over STOMP. */
@Controller
class CoordinateController(
    private val ingestService: DriverLocationIngestService,
) {

    @MessageMapping("/location")
    fun updateDriverLocation(
        @Payload currentLocation: DriverLocation,
        principal: Principal,
    ) {
        val driver = (principal as? StompPrincipal)?.recipient as? Recipient.Driver
            ?: throw MessagingException("Chỉ tài xế đã xác thực mới được gửi vị trí")

        ingestService.ingest(driver.id, currentLocation)
    }
}
