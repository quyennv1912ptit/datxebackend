package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import java.time.Instant
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.port.RideAssignmentPort
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisTtl
import com.nhom15.datxebackend.notification.dto.RideOfferPayload
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class RideAcceptanceService(
    private val redisTemplate: StringRedisTemplate,
    private val rideAssignmentPort: RideAssignmentPort,
    private val rideRepository: RideRepository,
    private val dispatchService: RideDispatchService,
    private val releaseService: OfferReleaseService,
    private val rideOfferBuilder: RideOfferBuilder,
    private val properties: MatchingProperties,
    private val offers: DispatchOfferRepository,
) {

    fun pendingOffer(driverId: Long): RideOfferPayload? {
        if (driverId <= 0) return null
        val driver = driverId.toString()
        val rideId = redisTemplate.opsForValue().get(RedisKeys.driverLock(driver))?.toLongOrNull() ?: return null
        val ride = rideId.toString()
        val state = redisTemplate.opsForHash<String, String>().entries(RedisKeys.rideState(ride))
        if (state["status"] != RideStatus.SEARCHING.name || state["currentDriver"] != driver) return null
        val offerId = state["currentOfferId"] ?: return null
        // The durable offer ledger is the source of truth for expiry. Reading the Redis
        // timeout index by a hand-built member key silently returned null after the member
        // format gained the offerId suffix, which broke REST recovery of a live offer.
        val durableOffer = offers.outstanding(rideId) ?: return null
        if (durableOffer.offerId != offerId || durableOffer.driverId != driverId) return null
        val expiresAt = durableOffer.expiresAt.toEpochMilli()
        if (expiresAt <= System.currentTimeMillis()) return null
        val databaseRide = rideRepository.findById(rideId).orElse(null) ?: return null
        if (databaseRide.status != RideStatus.SEARCHING || databaseRide.searchExpiresAt?.isAfter(Instant.now()) != true) return null
        return rideOfferBuilder.build(rideId, driverId, expiresAt, offerId)
    }

    fun accept(rideId: Long, driverId: Long, offerId: String): Boolean {
        val current = rideRepository.findById(rideId).orElse(null)
        if (current?.driverId == driverId && current.status in ACTIVE_RIDE_STATUSES && current.status != RideStatus.SEARCHING) return true
        val id = rideId.toString()

        val accepted = redisTemplate.execute(
            DispatchScripts.ACCEPT,
            listOf(
                RedisKeys.rideState(id),
                RedisKeys.DISPATCH_TIMEOUTS,
                RedisKeys.rideCandidates(id)
            ),
            driverId.toString(),
            id,
            System.currentTimeMillis().toString(), offerId
        ) == 1L

        if (!accepted) return false

        val assignment = try {
            rideAssignmentPort.assignDriver(rideId, driverId)
        } catch (exception: Exception) {
            // A listener can fail after the DB commit. Treat that case as success if DB confirms it.
            val currentRide = runCatching { rideRepository.findById(rideId).orElse(null) }.getOrNull()
            // If DB is unavailable, leave the lease intact. The durable tick reconciles once DB recovers.
            if (currentRide == null) throw exception
            if (currentRide.status in ACTIVE_RIDE_STATUSES && currentRide.status != RideStatus.SEARCHING && currentRide.driverId == driverId) {
                redisTemplate.delete(RedisKeys.rideCandidates(id))
                return true
            }
            rollbackRedisAcceptance(id, rideId, driverId, currentRide.status)
            throw exception
        }

        if (assignment == null) {
            val currentRide = rideRepository.findById(rideId).orElse(null)
            rollbackRedisAcceptance(id, rideId, driverId, currentRide?.status)
            throw ConflictException(ErrorCode.RIDE_NO_LONGER_AVAILABLE)
        }

        redisTemplate.delete(RedisKeys.rideCandidates(id))

        return true
    }

    private fun rollbackRedisAcceptance(
        id: String,
        rideId: Long,
        driverId: Long,
        databaseStatus: RideStatus?,
    ) {
        val status = databaseStatus?.name ?: "MISSING"
        redisTemplate.execute(
            DispatchScripts.ROLLBACK_ACCEPT,
            listOf(
                RedisKeys.rideState(id),
                RedisKeys.DISPATCH_TIMEOUTS,
                RedisKeys.rideRejected(id),
                RedisKeys.rideCandidates(id),
                RedisKeys.GEO_KEY,
            ),
            driverId.toString(),
            id,
            status,
            properties.rejectedTtl.seconds.toString(),
        )
        offers.outstanding(rideId)?.takeIf { it.driverId == driverId }?.let {
            offers.close(it.offerId, "CANCELLED", driverId, rideId)
        }
        if (databaseStatus == RideStatus.SEARCHING) {
            dispatchService.sendOfferToNextDriver(rideId)
        }
    }

    fun reject(rideId: Long, driverId: Long, offerId: String) {
        if (releaseService.release(rideId, driverId, "rejected", offerId)) {
            dispatchService.sendOfferToNextDriver(rideId)
        }
    }
}
