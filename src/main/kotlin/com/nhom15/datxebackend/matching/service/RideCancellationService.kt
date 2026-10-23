package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.OfferClosedPayload
import com.nhom15.datxebackend.ride.event.RideStatusChanged
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Service
class RideCancellationService(
    private val redisTemplate: StringRedisTemplate,
    private val notificationPort: NotificationPort
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onStatusChanged(e: RideStatusChanged) {
        if (e.to != RideStatus.CANCELLED || e.driverId != null) return   // đã có tài xế thì DriverReleaseService lo

        val id = e.rideId.toString()
        val offeredDriver = redisTemplate.execute(
            DispatchScripts.CANCEL_SEARCH,
            listOf(RedisKeys.rideState(id), RedisKeys.DISPATCH_TIMEOUTS, RedisKeys.rideCandidates(id), RedisKeys.GEO_KEY),
            id
        )
        if (!offeredDriver.isNullOrEmpty()) {
            notificationPort.send(Recipient.Driver(offeredDriver.toLong()), OfferClosedPayload.cancelled(e.rideId))
        }
    }
}