package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.port.NearbyDriverQueryPort
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisTtl
import com.nhom15.datxebackend.config.properties.DriverRanking
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.config.properties.ScheduledRideProperties
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.ride.event.RideNoDriverFound
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.*
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class RideDispatchService(
    private val nearbyDriverQueryPort: NearbyDriverQueryPort,
    private val selectionService: DriverSelectionService,
    private val matchingProperties: MatchingProperties,
    private val redisTemplate: StringRedisTemplate,
    private val publisher: ApplicationEventPublisher,
    private val rideOfferBuilder: RideOfferBuilder,
    private val notificationPort: NotificationPort,
    private val releaseService: OfferReleaseService,
    private val rideRepository: RideRepository,
    private val outbox: RideDispatchOutboxRepository,
    private val offers: DispatchOfferRepository,
    private val scheduled: ScheduledRideProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val minResumeDelay = Duration.ofMillis(100)

    /** null means terminal; otherwise the leased durable job is scheduled again. */
    fun dispatchRide(rideId: Long): Duration? {
        val initial = rideRepository.findById(rideId).orElse(null) ?: return null
        if (initial.status != RideStatus.SEARCHING) return null
        val id = rideId.toString()
        val token = UUID.randomUUID().toString()
        if (redisTemplate.opsForValue().setIfAbsent(RedisKeys.rideTick(id), token, matchingProperties.tickLockTtl) != true) {
            return matchingProperties.rescanDelay
        }
        try {
            val ride = rideRepository.findById(rideId).orElse(null) ?: return null
            if (ride.status != RideStatus.SEARCHING) return null
            val expires = ride.searchExpiresAt ?: return matchingProperties.rescanDelay
            if (!clock.instant().isBefore(expires)) {
                publisher.publishEvent(RideNoDriverFound(rideId))
                return null
            }
            val isScheduled = ride.type == RideType.SCHEDULED
            redisTemplate.execute(DispatchScripts.INITIALIZE_SEARCH, listOf(RedisKeys.rideState(id)),
                ride.status.name, ride.customerId.toString(), ride.transmission.name, ride.vehicleType.name,
                ride.pickup.geoPoint.lat.toString(), ride.pickup.geoPoint.lng.toString(),
                RedisTtl.RIDE_STATE.seconds.toString(), ride.redispatchCount.toString())

            val outstanding = offers.outstanding(rideId)
            if (outstanding != null) {
                val offerDeadline = outstanding.expiresAt.plus(matchingProperties.offerGrace)
                if (clock.instant().isBefore(offerDeadline)) {
                    // Even after Redis loss, a durable outstanding offer prevents a second offer.
                    // Wake up exactly for OFFER_TIMEOUT instead of polling every rescan delay.
                    return offerWakeDelay(offerDeadline, expires, isScheduled)
                }
                releaseService.expire(outstanding)
            }
            val hash = redisTemplate.opsForHash<String, String>()
            if (hash.get(RedisKeys.rideState(id), "status") != "SEARCHING") return matchingProperties.rescanDelay
            val orphanDriver = hash.get(RedisKeys.rideState(id), "currentDriver")?.toLongOrNull()
            if (orphanDriver != null) {
                val orphanToken = hash.get(RedisKeys.rideState(id), "currentOfferId")
                val orphanDeadline = hash.get(RedisKeys.rideState(id), "offerExpiresAt")?.toLongOrNull() ?: Long.MAX_VALUE
                if (orphanToken != null && orphanDeadline + matchingProperties.offerGrace.toMillis() <= clock.millis()) {
                    releaseService.release(rideId, orphanDriver, "ignored", orphanToken)
                } else return matchingProperties.rescanDelay
            }
            val remaining = timeUntil(expires)
            if (remaining < matchingProperties.offerTimeout) {
                return if (isScheduled) remaining else minOf(matchingProperties.rescanDelay, remaining)
            }
            val attempted = offers.attemptedDrivers(rideId)
            val needsScan = redisTemplate.opsForList().size(RedisKeys.rideCandidates(id)) == 0L
            val round = hash.get(RedisKeys.rideState(id), "nextRoundIndex")?.toIntOrNull() ?: 0
            if (needsScan) {
                val radius = matchingProperties.searchRadiiKm[minOf(round, matchingProperties.searchRadiiKm.lastIndex)]
                val nearby = nearbyDriverQueryPort.findNearbyDrivers(GeoPoint(ride.pickup.geoPoint.lat, ride.pickup.geoPoint.lng),
                    radius, matchingProperties.candidateScanLimit.toLong() + attempted.size)
                val eligible = selectionService.filterAndRankDrivers(nearby, ride.vehicleType, ride.transmission, radius)
                val ignored = offers.ignoredCounts(eligible.map { it.driverId }, matchingProperties.ignoredWindow)
                val ordered = if (matchingProperties.rankingMode == DriverRanking.DISTANCE) {
                    eligible.sortedWith(compareBy(
                        { (ignored[it.driverId] ?: 0) >= matchingProperties.ignoredThreshold },
                        { it.distance },
                    ))
                } else {
                    eligible.sortedByDescending {
                        it.score - if ((ignored[it.driverId] ?: 0) >= matchingProperties.ignoredThreshold) matchingProperties.ignoredPenalty else 0.0
                    }
                }
                val ranked = ordered.map { it.driverId.toString() }
                    .filterNot { it in attempted }.take(matchingProperties.maxCandidatesPerRound)
                log.info("Dispatch scan ride={} round={} radiusKm={} nearby={} eligible={} selected={} attempted={}",
                    rideId, round, radius, nearby.size, eligible.size, ranked.size, attempted.size)
                redisTemplate.execute(DispatchScripts.COMPLETE_ROUND,
                    listOf(RedisKeys.rideState(id), RedisKeys.rideCandidates(id)), round.toString(),
                    RedisTtl.RIDE_CANDIDATES.seconds.toString(), *ranked.toTypedArray())
            }
            val deadline = clock.instant().plus(matchingProperties.offerTimeout)
            if (deadline > expires) return if (isScheduled) timeUntil(expires) else matchingProperties.rescanDelay
            val offerId = UUID.randomUUID().toString()
            val next = redisTemplate.execute(DispatchScripts.OFFER_NEXT,
                listOf(RedisKeys.rideState(id), RedisKeys.rideCandidates(id), RedisKeys.rideRejected(id), RedisKeys.DISPATCH_TIMEOUTS),
                id, deadline.toEpochMilli().toString(), matchingProperties.offerTimeout.toMillis().toString(),
                matchingProperties.rejectedTtl.seconds.toString(), offerId, token)
            val driverId = next?.toLongOrNull()
            if (driverId == null) {
                val maxRound = matchingProperties.searchRadiiKm.lastIndex
                val maxRadiusReached = round > maxRound || (needsScan && round == maxRound)
                if (isScheduled && maxRadiusReached) return minOf(scheduled.idleRescanDelay, timeUntil(expires))
                return matchingProperties.rescanDelay
            }
            val offer = DispatchOffer(offerId, rideId, driverId, deadline, ride.redispatchCount)
            if (!offers.record(offer)) {
                releaseService.release(rideId, driverId, "unreachable", offerId)
                return matchingProperties.rescanDelay
            }
            log.info("Dispatch offer={} ride={} driver={} generation={} deadline={}", offerId, rideId, driverId, ride.redispatchCount, deadline)
            // Do not retract an offer when notification channels fail: REST polling is a valid fallback.
            runCatching { notificationPort.send(Recipient.Driver(driverId), rideOfferBuilder.build(rideId, driverId, deadline.toEpochMilli(), offerId)) }
                .onFailure { log.warn("Offer {} remains available via REST after notification failure", offerId, it) }
            // The durable outbox job is the OFFER_TIMEOUT job: wake at expires_at + grace
            // instead of re-running this tick every rescan delay while the offer is pending.
            return offerWakeDelay(deadline.plus(matchingProperties.offerGrace), expires, isScheduled)
        } finally {
            redisTemplate.execute(DispatchScripts.UNLOCK_TICK, listOf(RedisKeys.rideTick(id)), token)
        }
    }

    private fun timeUntil(deadline: Instant): Duration =
        Duration.between(clock.instant(), deadline).coerceAtLeast(Duration.ZERO)

    private fun offerWakeDelay(offerDeadline: Instant, searchDeadline: Instant, isScheduled: Boolean): Duration {
        val wakeAt = if (isScheduled) minOf(offerDeadline, searchDeadline) else offerDeadline
        return timeUntil(wakeAt).coerceAtLeast(minResumeDelay)
    }

    fun sendOfferToNextDriver(rideId: Long) = outbox.schedule(rideId)
}
