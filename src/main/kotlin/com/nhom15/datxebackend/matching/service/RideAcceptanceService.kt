package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
import com.nhom15.datxebackend.ride.repository.DispatchOffer
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import java.time.Instant
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.port.RideAssignmentPort
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.notification.dto.RideOfferPayload
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

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
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

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
        val id = rideId.toString()
        var reservedOffer: DispatchOffer? = null
        val accepted = try {
            transaction.execute {
                // Reject and expiry take the same row lock before changing either offer store.
                val ride = rideRepository.findByIdForUpdate(rideId).orElse(null) ?: return@execute false
                if (ride.status != RideStatus.SEARCHING) {
                    return@execute ride.driverId == driverId && ride.status in ACTIVE_RIDE_STATUSES &&
                        offers.accepted(offerId, rideId, driverId, ride.redispatchCount)
                }
                val offer = offers.outstanding(rideId) ?: return@execute false
                if (offer.offerId != offerId || offer.driverId != driverId || offer.generation != ride.redispatchCount) {
                    return@execute false
                }
                val reserved = redisTemplate.execute(
                    DispatchScripts.ACCEPT,
                    listOf(RedisKeys.rideState(id), RedisKeys.DISPATCH_TIMEOUTS, RedisKeys.rideCandidates(id)),
                    driverId.toString(), id, System.currentTimeMillis().toString(), offerId,
                ) == 1L
                if (!reserved) return@execute false

                reservedOffer = offer
                if (rideAssignmentPort.assignDriver(rideId, driverId) == null) {
                    throw ConflictException(ErrorCode.RIDE_NO_LONGER_AVAILABLE)
                }
                true
            } == true
        } catch (failure: Exception) {
            val offer = reservedOffer ?: throw failure
            val recovered = try {
                recoverAcceptance(offer)
            } catch (recoveryFailure: Exception) {
                // If the database is unavailable, the durable tick can reconcile the reservation later.
                failure.addSuppressed(recoveryFailure)
                throw failure
            }
            if (!recovered) throw failure
            true
        }

        if (accepted && reservedOffer != null) redisTemplate.delete(RedisKeys.rideCandidates(id))
        return accepted
    }

    private fun recoverAcceptance(offer: DispatchOffer): Boolean {
        val recovery = transaction.execute {
            val ride = rideRepository.findByIdForUpdate(offer.rideId).orElse(null)
            val status = ride?.status
            // An error after commit must preserve the durable receipt for this exact offer.
            if (ride?.driverId == offer.driverId && status in ACTIVE_RIDE_STATUSES && status != RideStatus.SEARCHING &&
                offers.accepted(offer.offerId, offer.rideId, offer.driverId, ride.redispatchCount)
            ) return@execute AcceptanceRecovery.COMMITTED

            val id = offer.rideId.toString()
            redisTemplate.execute(
                DispatchScripts.ROLLBACK_ACCEPT,
                listOf(RedisKeys.rideState(id), RedisKeys.DISPATCH_TIMEOUTS, RedisKeys.rideRejected(id),
                    RedisKeys.rideCandidates(id), RedisKeys.GEO_KEY),
                offer.driverId.toString(), id, status?.name ?: "MISSING",
                properties.rejectedTtl.seconds.toString(), offer.offerId,
            )
            offers.close(offer.offerId, "CANCELLED", offer.driverId, offer.rideId)
            if (status == RideStatus.SEARCHING) AcceptanceRecovery.RETRY else AcceptanceRecovery.ROLLBACK
        }
        if (recovery == AcceptanceRecovery.RETRY) dispatchService.sendOfferToNextDriver(offer.rideId)
        return recovery == AcceptanceRecovery.COMMITTED
    }

    private enum class AcceptanceRecovery { COMMITTED, RETRY, ROLLBACK }

    fun reject(rideId: Long, driverId: Long, offerId: String) {
        if (releaseService.release(rideId, driverId, "rejected", offerId)) {
            dispatchService.sendOfferToNextDriver(rideId)
        }
    }
}
