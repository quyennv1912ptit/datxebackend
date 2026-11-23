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
        if (!released) {
            // RELEASE trả 0 khi ACCEPT đã thắng ở Redis. Đừng đóng offer DB ở đây:
            // RideAcceptanceService cần offer vẫn OFFERED để CAS gán tài xế thành công,
            // sau đó path thành công sẽ đóng offer với ACCEPTED.
            return false
        }
        // Chỉ đóng durable offer khi script Redis xác nhận đây vẫn là lời mời hiện tại.
        offers.close(offerId, if (stat == "rejected") "REJECTED" else "IGNORED", driverId, rideId)
        return true
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
            val released = release(offer.rideId, offer.driverId, "ignored", offer.offerId)
            if (released) {
                runCatching { notificationPort.send(Recipient.Driver(offer.driverId), OfferClosedPayload.expired(offer.rideId)) }
            } else {
                // Khác với reject(): expire() đã xử lý riêng cửa sổ Redis ACCEPT ở trên.
                // Nếu state/timeout Redis đã mất (restart/eviction), RELEASE trả 0 và
                // release() cố tình không đóng offer DB. Phải đóng ở đây, nếu không
                // outstanding() có thể trả offer hết hạn này cho vòng dispatch sau.
                offers.close(offer.offerId, "IGNORED", offer.driverId, offer.rideId)
            }
        }
    }
}
