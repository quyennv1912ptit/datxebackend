package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.matching.service.DriverReleaseService
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.ScanOptions
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.state.*
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
    private val properties: MatchingProperties,
    private val offers: DispatchOfferRepository,
    private val drivers: DriverRepository,
    private val driverRelease: DriverReleaseService,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)
    private val startedAt = Instant.now()

    @Scheduled(fixedDelayString = "\${ride.dispatch.recovery-interval-ms:10000}")
    fun recoverLostDispatches() {
        rideRepository.findByStatusAndScheduledAtIsNullAndUpdatedAtBefore(RideStatus.PENDING, Instant.now().minusSeconds(30))
            .forEach { candidate -> safely(candidate.id) {
                transactions.executeWithoutResult {
                    val ride = rideRepository.findByIdForUpdate(candidate.id).orElse(null) ?: return@executeWithoutResult
                    if (ride.status == RideStatus.PENDING && ride.scheduledAt == null) {
                        stateMachine.transition(RideTransitionCommand(ride.id, RideTransitionEvent.START_SEARCH, SYSTEM_RIDE_ACTOR))
                    }
                }
            } }
        rideRepository.findByStatus(RideStatus.SEARCHING).forEach { ride -> safely(ride.id) {
            if (ride.searchExpiresAt?.plusSeconds(10)?.isBefore(Instant.now()) == true) {
                stateMachine.transition(RideTransitionCommand(ride.id, RideTransitionEvent.NO_DRIVER_FOUND, SYSTEM_RIDE_ACTOR, expectedSearchGeneration = ride.redispatchCount))
            } else dispatchOutbox.recover(ride.id)
        } }
        // Restore BUSY exclusively from DB. Never place an assigned driver back in GEO.
        val assigned = ACTIVE_RIDE_STATUSES.filterNot { it == RideStatus.SEARCHING }
        var page = 0
        do {
            val batch = rideRepository.findAllByStatusIn(assigned, PageRequest.of(page++, 200))
            batch.content.forEach { candidate -> safely(candidate.id) {
                transactions.executeWithoutResult {
                val ride = rideRepository.findByIdForUpdate(candidate.id).orElse(null) ?: return@executeWithoutResult
                if (ride.status !in assigned) return@executeWithoutResult
                val driverId = ride.driverId ?: return@executeWithoutResult
                drivers.findByIdForUpdate(driverId).orElse(null)?.let { driver ->
                    if (driver.status != DriverStatus.BUSY) {
                        driver.status = DriverStatus.BUSY
                        drivers.save(driver)
                    }
                }
                val id = driverId.toString()
                redisTemplate.opsForValue().set(RedisKeys.driverCurrentRide(id), ride.id.toString())
                redisTemplate.opsForValue().set(RedisKeys.driverStatus(id), "BUSY")
                redisTemplate.opsForGeo().remove(RedisKeys.GEO_KEY, id)
                val seen = redisTemplate.opsForZSet().score(RedisKeys.DRIVERS_LASTSEEN, id)?.toLong()?.let(Instant::ofEpochMilli)
                val lastContact = listOfNotNull(seen, ride.acceptedAt, startedAt).maxOrNull()!!
                if (ride.status in setOf(RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVING) &&
                    Duration.between(lastContact, Instant.now()) > properties.driverDisconnectTimeout) {
                    stateMachine.transition(RideTransitionCommand(ride.id, RideTransitionEvent.REDISPATCH,
                        SYSTEM_RIDE_ACTOR, note = "Tài xế mất kết nối quá hạn; tìm tài xế khác"))
                }
                }
            } }
        } while (batch.hasNext())
        drivers.findByStatus(DriverStatus.BUSY).forEach { candidate -> safely(candidate.id) {
            transactions.executeWithoutResult {
                val driver = drivers.findByIdForUpdate(candidate.id).orElse(null) ?: return@executeWithoutResult
                if (rideRepository.findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(driver.id, assigned) == null && !offers.driverHasOffer(driver.id)) {
                    driver.status = DriverStatus.ONLINE
                    drivers.save(driver)
                    val current = redisTemplate.opsForValue().get(RedisKeys.driverCurrentRide(driver.id.toString())) ?: ""
                    driverRelease.reconcileIdle(driver.id, current)
                }
            }
        } }
        // Repair orphaned BUSY/OFFERED Redis records. Lua rechecks ownership before changing anything.
        redisTemplate.scan(ScanOptions.scanOptions().match("driver:*:status").count(200).build()).use { cursor ->
            while (cursor.hasNext()) {
                val key = cursor.next()
                val driverId = key.removePrefix("driver:").removeSuffix(":status").toLongOrNull() ?: continue
                safely(driverId) {
                    val status = redisTemplate.opsForValue().get(key)
                    if (status !in setOf("BUSY", "ON_TRIP", "OFFERED")) return@safely
                    val current = redisTemplate.opsForValue().get(RedisKeys.driverCurrentRide(driverId.toString())) ?: ""
                    val active = rideRepository.findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(driverId, assigned)
                    if (active == null && !offers.driverHasOffer(driverId)) driverRelease.reconcileIdle(driverId, current)
                }
            }
        }
    }

    private fun safely(id: Long, block: () -> Unit) {
        runCatching(block).onFailure { log.warn("Dispatch reconciliation failed for {}", id, it) }
    }
}
