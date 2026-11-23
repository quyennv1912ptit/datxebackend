package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.OfferClosedPayload
import com.nhom15.datxebackend.ride.repository.DispatchOffer
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Service
class OfferReleaseService(
    private val redisTemplate: StringRedisTemplate,
    private val properties: MatchingProperties,
    private val offers: DispatchOfferRepository,
    private val notificationPort: NotificationPort,
    private val rides: RideRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun release(rideId: Long, driverId: Long, stat: String, offerId: String): Boolean = transaction.execute {
        val ride = rides.findByIdForUpdate(rideId).orElse(null) ?: return@execute false
        if (ride.status != RideStatus.SEARCHING) return@execute false
        releaseLocked(rideId, driverId, stat, offerId, ride.redispatchCount)
    } == true

    private fun releaseLocked(rideId: Long, driverId: Long, stat: String, offerId: String, generation: Int): Boolean {
        val id = rideId.toString()
        val released = redisTemplate.execute(DispatchScripts.RELEASE,
            listOf(RedisKeys.rideState(id), RedisKeys.DISPATCH_TIMEOUTS, RedisKeys.rideRejected(id), RedisKeys.GEO_KEY),
            driverId.toString(), id, stat, properties.rejectedTtl.seconds.toString(), offerId) == 1L
        if (!released) {
            val outstanding = offers.outstanding(rideId)
            // If acceptance stopped before PostgreSQL committed, clear its Redis reservation before closing the receipt.
            if (outstanding?.offerId == offerId && outstanding.driverId == driverId && outstanding.generation == generation) {
                redisTemplate.execute(DispatchScripts.ROLLBACK_ACCEPT,
                    listOf(RedisKeys.rideState(id), RedisKeys.DISPATCH_TIMEOUTS, RedisKeys.rideRejected(id),
                        RedisKeys.rideCandidates(id), RedisKeys.GEO_KEY),
                    driverId.toString(), id, RideStatus.SEARCHING.name, properties.rejectedTtl.seconds.toString(), offerId)
            }
        }
        // Closing the durable offer is conditional too; a delayed old timeout cannot close a newer one.
        val closed = offers.close(offerId, if (stat == "rejected") "REJECTED" else "IGNORED", driverId, rideId)
        return released || closed
    }

    fun expire(offer: DispatchOffer) {
        val released = transaction.execute {
            val ride = rides.findByIdForUpdate(offer.rideId).orElse(null) ?: return@execute false
            if (ride.status != RideStatus.SEARCHING || offers.outstanding(offer.rideId) != offer) return@execute false
            releaseLocked(offer.rideId, offer.driverId, "ignored", offer.offerId, ride.redispatchCount)
        }
        if (released == true) {
            runCatching { notificationPort.send(Recipient.Driver(offer.driverId), OfferClosedPayload.expired(offer.rideId)) }
        }
    }
}
