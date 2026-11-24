package com.nhom15.datxebackend.matching.service

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisTtl
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.DriverAccountNotificationPayload
import com.nhom15.datxebackend.ride.event.RideStatusChanged
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Service
class DriverReleaseService @Autowired constructor(
    private val redisTemplate: StringRedisTemplate,
    private val drivers: DriverRepository,
    private val notificationPort: NotificationPort? = null,
    private val firebaseAuth: FirebaseAuth? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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

        val driverNumericId = driverId.toLong()
        val currentKey = RedisKeys.driverCurrentRide(driverId)
        if (redisTemplate.opsForValue().get(currentKey) == id) {
            reconcileIdle(driverNumericId, id)
        }
        if (finalStatus != RideStatus.SEARCHING.name) {
            enforcePostRideAccountStatus(driverNumericId)
        }
    }

    fun reconcileIdle(driverId: Long, expectedRide: String) {
        val status = drivers.findById(driverId).orElse(null)?.status ?: DriverStatus.OFFLINE
        redisTemplate.execute(DispatchScripts.RECONCILE_IDLE, listOf(RedisKeys.GEO_KEY), driverId.toString(), expectedRide, status.name)
    }

    private fun enforcePostRideAccountStatus(driverId: Long) {
        val driver = drivers.findById(driverId).orElse(null) ?: return
        if (driver.accountStatus !in setOf("SUSPENDED", "BANNED")) return
        val payloadType = if (driver.accountStatus == "BANNED") {
            PayloadType.DRIVER_ACCOUNT_STATUS_UPDATED
        } else {
            PayloadType.DRIVER_FORCE_OFFLINE
        }
        runCatching {
            notificationPort?.send(
                Recipient.Driver(driverId),
                DriverAccountNotificationPayload(
                    type = payloadType,
                    status = driver.status.name,
                    reason = driver.accountStatusReason,
                    accountStatus = driver.accountStatus,
                    withdrawalFrozen = driver.withdrawalFrozen,
                ),
            )
        }.onFailure { log.warn("Không gửi được thông báo sau chuyến cho tài xế {}", driverId, it) }

        if (driver.accountStatus == "BANNED") {
            try {
                firebaseAuth?.revokeRefreshTokens(driver.firebaseUid)
            } catch (e: FirebaseAuthException) {
                log.warn("Không thu hồi được Firebase refresh token sau chuyến cho tài xế {}", driverId, e)
            } catch (e: RuntimeException) {
                log.warn("Lỗi thu hồi Firebase refresh token sau chuyến cho tài xế {}", driverId, e)
            }
        }
    }
}
