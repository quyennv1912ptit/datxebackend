package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisTtl
import com.nhom15.datxebackend.ride.event.RideStatusChanged
import org.springframework.data.geo.Point
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Service
class DriverReleaseService(private val redisTemplate: StringRedisTemplate) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onStatusChanged(e: RideStatusChanged) {
        when (e.to) {
            RideStatus.COMPLETED -> releaseAfterRide(e.rideId, "COMPLETED")
            RideStatus.CANCELLED -> releaseAfterRide(e.rideId, "CANCELLED")
            else -> Unit
        }
    }

    private fun releaseAfterRide(rideId: Long, finalStatus: String) {
        val id = rideId.toString()
        val stateKey = RedisKeys.rideState(id)
        val hash = redisTemplate.opsForHash<String, String>()

        val driverId = hash.get(stateKey, "driverId") ?: return

        hash.put(stateKey, "status", finalStatus)
        redisTemplate.expire(stateKey, RedisTtl.RIDE_AFTER_END)
        redisTemplate.expire(RedisKeys.rideEvents(id), RedisTtl.RIDE_AFTER_END)

        val currentKey = RedisKeys.driverCurrentRide(driverId)
        if (redisTemplate.opsForValue().get(currentKey) != id) return
        redisTemplate.delete(currentKey)
        returnToPool(driverId)
    }

    private fun returnToPool(driverId: String) {
        if (redisTemplate.hasKey(RedisKeys.driverAlive(driverId)) != true) {
            redisTemplate.opsForValue().set(RedisKeys.driverStatus(driverId), "OFFLINE")
            return
        }

        redisTemplate.opsForValue().set(RedisKeys.driverStatus(driverId), "ONLINE")
        val loc = redisTemplate.opsForHash<String, String>().entries(RedisKeys.driverLocation(driverId))
        val lat = loc["lat"]?.toDoubleOrNull() ?: return
        val lng = loc["lng"]?.toDoubleOrNull() ?: return
        redisTemplate.opsForGeo().add(RedisKeys.GEO_KEY, Point(lng, lat), driverId)
    }
}