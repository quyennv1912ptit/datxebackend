package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.config.properties.MatchingProperties
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class OfferTimeoutTask(
    private val redisTemplate: StringRedisTemplate,
    private val dispatchService: RideDispatchService,
    private val properties: MatchingProperties,
) {
    @Scheduled(fixedDelayString = "\${app.matching.timeout-poll-ms:1000}")
    fun handleExpiredOffers() {
        val now = (System.currentTimeMillis() - properties.offerGrace.toMillis()).toDouble()
        redisTemplate.opsForZSet().rangeByScore(RedisKeys.DISPATCH_TIMEOUTS, 0.0, now, 0, 100)?.forEach { member ->
            val rideId = member.substringBefore(':').toLongOrNull()
            if (rideId != null) dispatchService.sendOfferToNextDriver(rideId)
            // Durable DB offer drives cleanup; a dead/stale index entry must not enqueue forever.
            redisTemplate.opsForZSet().remove(RedisKeys.DISPATCH_TIMEOUTS, member)
        }
    }
}
