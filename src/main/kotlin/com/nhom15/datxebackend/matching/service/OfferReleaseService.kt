package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisTtl
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class OfferReleaseService(private val redisTemplate: StringRedisTemplate) {
    fun release(rideId: Long, driverId: Long, stat: String): Boolean {
        val id = rideId.toString()
        return redisTemplate.execute(
            DispatchScripts.RELEASE,
            listOf(RedisKeys.rideState(id), RedisKeys.DISPATCH_TIMEOUTS, RedisKeys.rideRejected(id), RedisKeys.GEO_KEY),
            driverId.toString(), id, stat, RedisTtl.RIDE_STATE.seconds.toString()
        ) == 1L
    }
}