package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.ride.service.state.RideTransitionCommand
import com.nhom15.datxebackend.ride.service.state.RideTransitionEvent
import com.nhom15.datxebackend.ride.service.state.SYSTEM_RIDE_ACTOR
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

@Component
class RideDispatchRecoveryTask(
    private val rideRepository: RideRepository,
    private val stateMachine: RideStateMachine,
    private val dispatchOutbox: RideDispatchOutboxRepository,
    private val redisTemplate: StringRedisTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @Scheduled(fixedDelayString = "\${ride.dispatch.recovery-interval-ms:60000}")
    fun recoverLostDispatches() {
        val cutoff = Instant.now().minus(Duration.ofMinutes(2))

        rideRepository.findByStatusAndScheduledAtIsNullAndUpdatedAtBefore(RideStatus.PENDING, cutoff)
            .forEach { candidate ->
                runCatching {
                    transactionTemplate.executeWithoutResult {
                        val ride = rideRepository.findByIdForUpdate(candidate.id).orElse(null) ?: return@executeWithoutResult
                        if (ride.status != RideStatus.PENDING || ride.scheduledAt != null) return@executeWithoutResult
                        stateMachine.transition(
                            RideTransitionCommand(
                                rideId = ride.id,
                                event = RideTransitionEvent.START_SEARCH,
                                actor = SYSTEM_RIDE_ACTOR,
                                note = "Khôi phục dispatch chuyến tức thời bị gián đoạn",
                            ),
                        )
                        dispatchOutbox.enqueue(ride.id)
                    }
                }.onFailure { log.warn("Không khôi phục được chuyến PENDING {}", candidate.id, it) }
            }

        rideRepository.findByStatusAndUpdatedAtBefore(RideStatus.SEARCHING, cutoff)
            .forEach { ride ->
                val stateKey = RedisKeys.rideState(ride.id.toString())
                if (redisTemplate.hasKey(stateKey) != true) {
                    runCatching { dispatchOutbox.recover(ride.id) }
                        .onFailure { log.warn("Không khôi phục được outbox dispatch ride {}", ride.id, it) }
                }
            }
    }
}
