package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.OfferClosedPayload
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OfferTimeoutTask(
    private val redisTemplate: StringRedisTemplate,
    private val releaseService: OfferReleaseService,
    private val dispatchService: RideDispatchService,
    private val notificationPort: NotificationPort
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 1000)
    fun handleExpiredOffers() {
        val now = System.currentTimeMillis().toDouble()
        val due = redisTemplate.opsForZSet().rangeByScore(RedisKeys.DISPATCH_TIMEOUTS, 0.0, now) ?: return

        due.forEach { member ->
            try {
                val parts = member.split(":")
                val rideId = parts.getOrNull(0)?.toLongOrNull()
                val driverId = parts.getOrNull(1)?.toLongOrNull()
                if (rideId == null || driverId == null) {
                    log.warn("Mục timeout không hợp lệ, bỏ qua: {}", member)
                    redisTemplate.opsForZSet().remove(RedisKeys.DISPATCH_TIMEOUTS, member)
                    return@forEach
                }

                if (releaseService.release(rideId, driverId, "timeout")) {
                    notificationPort.send(Recipient.Driver(driverId), OfferClosedPayload.expired(rideId))
                    dispatchService.sendOfferToNextDriver(rideId)
                }
            } catch (e: Exception) {
                log.error("Lỗi xử lý offer hết hạn {}", member, e)
            }
        }
    }
}