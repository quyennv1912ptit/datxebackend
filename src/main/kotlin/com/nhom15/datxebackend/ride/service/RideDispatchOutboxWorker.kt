package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.matching.service.RideDispatchService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class RideDispatchOutboxWorker(
    private val outbox: RideDispatchOutboxRepository,
    private val dispatchService: RideDispatchService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.outbox.poll-interval-ms:1000}")
    fun dispatchPendingRides() {
        outbox.claimDue().forEach { rideId ->
            try {
                dispatchService.dispatchRide(rideId)
                outbox.markDelivered(rideId)
            } catch (exception: Exception) {
                log.error("Không dispatch được ride {}; sẽ thử lại", rideId, exception)
                runCatching { outbox.retry(rideId, exception.message ?: exception.javaClass.simpleName) }
                    .onFailure { log.error("Không lên lịch retry cho ride {}", rideId, it) }
            }
        }
    }
}
