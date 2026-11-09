package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.config.properties.ScheduledRideProperties
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
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
import java.time.Clock

@Component
class ScheduledRideScheduler(
    private val rideRepository: RideRepository,
    private val stateMachine: RideStateMachine,
    private val customers: CustomerRepository,
    private val properties: ScheduledRideProperties,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @Scheduled(fixedDelayString = "\${app.scheduled-ride.poll-interval-ms:30000}")
    fun processDueRides() {
        val dueRides = rideRepository.findByTypeAndStatusAndScheduledAtLessThanEqualOrderByScheduledAtAscIdAsc(
            RideType.SCHEDULED,
            RideStatus.PENDING,
            clock.instant().plus(properties.dispatchLeadTime),
        )

        dueRides.forEach { candidate ->
            try {
                transactionTemplate.executeWithoutResult {
                    // Serialize activation with ride creation for this customer.
                    if (customers.findByIdForUpdate(candidate.customerId).isEmpty) return@executeWithoutResult
                    val ride = rideRepository.findByIdForUpdate(candidate.id).orElse(null) ?: return@executeWithoutResult
                    if (ride.status != RideStatus.PENDING) return@executeWithoutResult
                    val scheduledAt = ride.scheduledAt ?: return@executeWithoutResult
                    val currentTime = clock.instant()

                    if (!currentTime.isBefore(scheduledAt.plus(properties.expireAfter))) {
                        stateMachine.transition(
                            RideTransitionCommand(
                                rideId = ride.id,
                                event = RideTransitionEvent.EXPIRE,
                                actor = SYSTEM_RIDE_ACTOR,
                                note = "Hết thời gian tìm tài xế cho chuyến đặt lịch",
                            ),
                        )
                        return@executeWithoutResult
                    }

                    if (rideRepository.existsByCustomerIdAndStatusIn(ride.customerId, ACTIVE_RIDE_STATUSES)) {
                        return@executeWithoutResult
                    }
                    stateMachine.transition(
                        RideTransitionCommand(
                            rideId = ride.id,
                            event = RideTransitionEvent.START_SEARCH,
                            actor = SYSTEM_RIDE_ACTOR,
                            note = "Đến thời điểm bắt đầu tìm tài xế cho chuyến đặt lịch",
                        ),
                    )
                }
            } catch (exception: Exception) {
                log.warn("Không xử lý được ride đặt lịch {}: {}", candidate.id, exception.message)
            }
        }
    }
}
