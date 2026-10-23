package com.nhom15.datxebackend.matching.service

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
) {

    fun pendingOffer(driverId: Long): RideOfferPayload? {
        if (driverId <= 0) return null
        val driver = driverId.toString()
        val rideId = redisTemplate.opsForValue().get(RedisKeys.driverLock(driver))?.toLongOrNull() ?: return null
        val ride = rideId.toString()
        val state = redisTemplate.opsForHash<String, String>().entries(RedisKeys.rideState(ride))
        if (state["status"] != RideStatus.SEARCHING.name || state["currentDriver"] != driver) return null
        val expiresAt = redisTemplate.opsForZSet().score(RedisKeys.DISPATCH_TIMEOUTS, "$ride:$driver")?.toLong()
            ?: return null
        if (expiresAt <= System.currentTimeMillis()) return null
        return rideOfferBuilder.build(rideId, driverId, expiresAt)
    }

    fun accept(rideId: Long, driverId: Long): Boolean {
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
            System.currentTimeMillis().toString()
        ) == 1L

        if (!accepted) return false

        val assignment = try {
            rideAssignmentPort.assignDriver(rideId, driverId)
        } catch (exception: Exception) {
            // A listener can fail after the DB commit. Treat that case as success if DB confirms it.
            val currentRide = rideRepository.findById(rideId).orElseThrow { exception }
            if (currentRide.status == RideStatus.DRIVER_ASSIGNED && currentRide.driverId == driverId) {
                redisTemplate.delete(RedisKeys.rideCandidates(id))
                return true
            }
            rollbackRedisAcceptance(id, rideId, driverId, currentRide.status)
            throw exception
        }

        if (assignment == null) {
            val currentRide = rideRepository.findById(rideId).orElse(null)
            rollbackRedisAcceptance(id, rideId, driverId, currentRide?.status)
            return false
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
            RedisTtl.RIDE_STATE.seconds.toString(),
        )
        if (databaseStatus == RideStatus.SEARCHING) {
            dispatchService.sendOfferToNextDriver(rideId)
        }
    }

    fun reject(rideId: Long, driverId: Long) {
        if (releaseService.release(rideId, driverId, "rejected")) {
            dispatchService.sendOfferToNextDriver(rideId)
        }
    }
}
