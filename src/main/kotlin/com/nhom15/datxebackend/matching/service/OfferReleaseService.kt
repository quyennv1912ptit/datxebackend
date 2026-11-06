package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisTtl
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.OfferClosedPayload
import com.nhom15.datxebackend.ride.repository.DispatchOffer
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

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

    fun release(rideId: Long, driverId: Long, stat: String, offerId: String): Boolean {
        val id = rideId.toString()
        val released = redisTemplate.execute(DispatchScripts.RELEASE,
            listOf(RedisKeys.rideState(id), RedisKeys.DISPATCH_TIMEOUTS, RedisKeys.rideRejected(id), RedisKeys.GEO_KEY),
            driverId.toString(), id, stat, properties.rejectedTtl.seconds.toString(), offerId) == 1L
        // Closing the durable offer is conditional too; a delayed old timeout cannot close a newer one.
        val closed = offers.close(offerId, if (stat == "rejected") "REJECTED" else "IGNORED", driverId, rideId)
        return released || closed
    }

    fun expire(offer: DispatchOffer) {
        transaction.executeWithoutResult {
            val ride = rides.findByIdForUpdate(offer.rideId).orElse(null) ?: return@executeWithoutResult
            if (ride.status != com.nhom15.datxebackend.common.enums.RideStatus.SEARCHING) return@executeWithoutResult
            val state = RedisKeys.rideState(offer.rideId.toString())
            // Recover the narrow crash window between Redis ACCEPT and the PostgreSQL transaction.
            if (redisTemplate.opsForHash<String, String>().get(state, "status") == "DRIVER_ASSIGNED") {
                redisTemplate.execute(DispatchScripts.ROLLBACK_ACCEPT,
                    listOf(state, RedisKeys.DISPATCH_TIMEOUTS, RedisKeys.rideRejected(offer.rideId.toString()),
                        RedisKeys.rideCandidates(offer.rideId.toString()), RedisKeys.GEO_KEY),
                    offer.driverId.toString(), offer.rideId.toString(), "SEARCHING", properties.rejectedTtl.seconds.toString())
            }
            if (release(offer.rideId, offer.driverId, "ignored", offer.offerId)) {
                runCatching { notificationPort.send(Recipient.Driver(offer.driverId), OfferClosedPayload.expired(offer.rideId)) }
            }
        }
    }
}
