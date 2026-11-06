package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.matching.service.RideDispatchService
import com.nhom15.datxebackend.ride.repository.DispatchJob
import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.ride.service.state.RideTransitionCommand
import com.nhom15.datxebackend.ride.service.state.RideTransitionEvent
import com.nhom15.datxebackend.ride.service.state.SYSTEM_RIDE_ACTOR
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

@Component
class RideDispatchOutboxWorker(
    private val outbox: RideDispatchOutboxRepository,
    private val dispatchService: RideDispatchService,
    private val stateMachine: RideStateMachine,
    private val properties: MatchingProperties,
    private val rides: RideRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)

    @Scheduled(fixedDelayString = "\${app.outbox.poll-interval-ms:1000}")
    fun dispatchPendingRides() {
        outbox.claimDue().forEach { job ->
            try {
                outbox.finish(job, dispatchService.dispatchRide(job.rideId))
            } catch (exception: Exception) {
                log.error("Không dispatch được ride {}; sẽ thử lại", job.rideId, exception)
                if (job.failures + 1 >= properties.maxTickFailures) {
                    runCatching { endFailedSearch(job) }
                        .onFailure { log.warn("Không kết thúc được dispatch lỗi cho ride {}", job.rideId, it) }
                }
                runCatching { outbox.finish(job, properties.rescanDelay, exception.message ?: exception.javaClass.simpleName) }
                    .onFailure { log.error("Không lên lịch retry cho ride {}", job.rideId, it) }
            }
        }
    }

    private fun endFailedSearch(job: DispatchJob) {
        transactions.executeWithoutResult {
            // Keep lock ordering consistent with start-search/redispatch: ride, then queue.
            val ride = rides.findByIdForUpdate(job.rideId).orElse(null) ?: return@executeWithoutResult
            if (ride.status != RideStatus.SEARCHING || ride.redispatchCount != job.generation || !outbox.lockCurrent(job)) {
                return@executeWithoutResult
            }
            val event = if (ride.searchExpiresAt?.isAfter(Instant.now()) == true) {
                RideTransitionEvent.SYSTEM_FAILURE
            } else RideTransitionEvent.NO_DRIVER_FOUND
            stateMachine.transition(RideTransitionCommand(
                job.rideId, event, SYSTEM_RIDE_ACTOR,
                note = "Dispatch unavailable after retries", expectedSearchGeneration = job.generation,
            ))
        }
    }
}
