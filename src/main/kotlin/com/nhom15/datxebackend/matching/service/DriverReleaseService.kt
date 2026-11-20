package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisTtl
import com.nhom15.datxebackend.ride.event.RideStatusChanged
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Service
class DriverReleaseService(
    private val redisTemplate: StringRedisTemplate,
    private val drivers: DriverRepository,
) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onStatusChanged(e: RideStatusChanged) {
        when (e.to) {
            RideStatus.COMPLETED -> releaseAfterRide(e.rideId, "COMPLETED", e.driverId)
            RideStatus.CANCELLED, RideStatus.NO_DRIVER_FOUND -> releaseAfterRide(e.rideId, e.to.name, e.driverId)
            RideStatus.SEARCHING -> if (e.driverId != null) releaseAfterRide(e.rideId, "SEARCHING", e.driverId)
            else -> Unit
        }
    }

    private fun releaseAfterRide(rideId: Long, finalStatus: String, eventDriverId: Long?) {
        val id = rideId.toString()
        val stateKey = RedisKeys.rideState(id)
        val hash = redisTemplate.opsForHash<String, String>()

        val driverId = eventDriverId?.toString() ?: hash.get(stateKey, "driverId") ?: return
        val currentOwner = hash.get(stateKey, "driverId")
        if (currentOwner != null && currentOwner != driverId) return

        if (finalStatus != RideStatus.SEARCHING.name) {
            redisTemplate.delete(listOf(RedisKeys.rideRejected(id), RedisKeys.rideCandidates(id), RedisKeys.rideCurrentOffer(id)))
        }
        hash.put(stateKey, "status", finalStatus)
        redisTemplate.expire(stateKey, RedisTtl.RIDE_AFTER_END)
        redisTemplate.expire(RedisKeys.rideEvents(id), RedisTtl.RIDE_AFTER_END)

        val currentKey = RedisKeys.driverCurrentRide(driverId)
        if (redisTemplate.opsForValue().get(currentKey) != id) return
        reconcileIdle(driverId.toLong(), id)
    }

    fun reconcileIdle(driverId: Long, expectedRide: String) {
        val status = drivers.findById(driverId).orElse(null)?.status ?: DriverStatus.OFFLINE
        redisTemplate.execute(DispatchScripts.RECONCILE_IDLE, listOf(RedisKeys.GEO_KEY), driverId.toString(), expectedRide, status.name)
    }
}
