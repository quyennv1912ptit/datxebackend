package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisKeys.rideCandidates
import com.nhom15.datxebackend.common.redis.RedisKeys.rideRejected
import com.nhom15.datxebackend.common.redis.RedisKeys.rideState
import com.nhom15.datxebackend.common.redis.RedisTtl
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.notification.api.DeliveryResult
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.common.port.NearbyDriverQueryPort
import com.nhom15.datxebackend.common.port.NearbyDriver
import com.nhom15.datxebackend.ride.event.RideNoDriverFound
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class RideDispatchService(
    private val nearbyDriverQueryPort: NearbyDriverQueryPort,
    private val selectionService: DriverSelectionService,
    private val matchingProperties: MatchingProperties,
    private val redisTemplate: StringRedisTemplate,
    private val publisher: ApplicationEventPublisher,
    private val rideOfferBuilder: RideOfferBuilder,
    private val notificationPort: NotificationPort,
    private val releaseService: OfferReleaseService,
    private val rideRepository: RideRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun dispatchRide(rideId: Long) {
        val ride = rideRepository.findById(rideId).orElse(null) ?: return
        if (ride.status != RideStatus.SEARCHING) return

        redisTemplate.execute(
            DispatchScripts.INITIALIZE_SEARCH,
            listOf(rideState(rideId.toString())),
            RideStatus.SEARCHING.name,
            ride.customerId.toString(),
            ride.transmission.name,
            ride.vehicleType.name,
            ride.pickup.geoPoint.lat.toString(),
            ride.pickup.geoPoint.lng.toString(),
            RedisTtl.RIDE_STATE.seconds.toString(),
        )
        sendOfferToNextDriver(rideId)
    }

    fun sendOfferToNextDriver(rideId: Long) {
        val id = rideId.toString()
        val keys = listOf(rideState(id), rideCandidates(id), rideRejected(id), RedisKeys.DISPATCH_TIMEOUTS)

        while (true) {
            val deadline = System.currentTimeMillis() + matchingProperties.offerTimeout.toMillis()

            val next = redisTemplate.execute(
                DispatchScripts.OFFER_NEXT,
                keys,
                id,
                deadline.toString(),
                RedisTtl.DRIVER_LOCK.seconds.toString(),
                RedisTtl.RIDE_STATE.seconds.toString(),
            ) ?: return

            when (next) {
                "NOT_SEARCHING" -> return
                "WAIT" -> return
                "ROUND_EXHAUSTED" -> {
                    when (prepareNextRound(id, rideId)) {
                        RoundPreparation.RETRY -> continue
                        RoundPreparation.WAIT, RoundPreparation.STOP -> return
                    }
                }
            }

            val driverIdStr = next
            val driverId = driverIdStr.toLong()

            val delivered = try {
                val payload = rideOfferBuilder.build(rideId, driverId, deadline)
                notificationPort.send(Recipient.Driver(driverId), payload) != DeliveryResult.UNREACHABLE
            } catch (e: Exception) {
                log.warn("Không dựng/gửi được offer chuyến {} cho tài xế {}: {}", rideId, driverId, e.message)
                false
            }

            if (delivered) {
                redisTemplate.opsForGeo().remove(RedisKeys.GEO_KEY, driverIdStr)
                return
            }

            if (!releaseService.release(rideId, driverId, "unreachable")) return
        }
    }

    private fun prepareNextRound(id: String, rideId: Long): RoundPreparation {
        val claim = redisTemplate.execute(
            DispatchScripts.ADVANCE_ROUND,
            listOf(rideState(id), rideCandidates(id)),
            *matchingProperties.searchRadiiKm.map(Double::toString).toTypedArray(),
        ) ?: return RoundPreparation.STOP

        when (claim) {
            "WAIT" -> return RoundPreparation.WAIT
            "NOT_SEARCHING" -> return RoundPreparation.STOP
            "FINISHED" -> {
                publisher.publishEvent(RideNoDriverFound(rideId))
                return RoundPreparation.STOP
            }
        }

        val parts = claim.split(':', limit = 2)
        val roundIndex = parts.getOrNull(0)?.toIntOrNull() ?: return RoundPreparation.STOP
        val radiusKm = parts.getOrNull(1)?.toDoubleOrNull() ?: return RoundPreparation.STOP
        val state = redisTemplate.opsForHash<String, String>().entries(rideState(id))
        val pickupLat = state["pickupLat"]?.toDoubleOrNull()
        val pickupLng = state["pickupLng"]?.toDoubleOrNull()
        val transmission = state["transmission"]?.let { runCatching { Transmission.valueOf(it) }.getOrNull() }
        val vehicleType = state["vehicleType"]?.let { runCatching { VehicleType.valueOf(it) }.getOrNull() }
        if (pickupLat == null || pickupLng == null || transmission == null || vehicleType == null) {
            return RoundPreparation.STOP
        }

        val triedIds = redisTemplate.opsForSet().members(rideRejected(id)).orEmpty()
        // Redis returns nearest-first. Account for already tried IDs so the next untried
        // candidates are still included when the radius query is capped.
        val queryLimit = matchingProperties.maxCandidatesPerRound.toLong() + triedIds.size.toLong()
        val nearbyDrivers: List<NearbyDriver> = nearbyDriverQueryPort.findNearbyDrivers(
            GeoPoint(lat = pickupLat, lng = pickupLng),
            radiusKm,
            queryLimit,
        )
        val rankedDriverIds = selectionService
            .filterAndRankDrivers(nearbyDrivers, vehicleType, transmission, radiusKm)
            .asSequence()
            .filterNot { it.driverId.toString() in triedIds }
            .take(matchingProperties.maxCandidatesPerRound)
            .map { it.driverId.toString() }
            .toList()

        val completed = redisTemplate.execute(
            DispatchScripts.COMPLETE_ROUND,
            listOf(rideState(id), rideCandidates(id)),
            roundIndex.toString(),
            RedisTtl.RIDE_CANDIDATES.seconds.toString(),
            *rankedDriverIds.toTypedArray(),
        ) == 1L
        return if (completed) RoundPreparation.RETRY else RoundPreparation.STOP
    }

    private enum class RoundPreparation { RETRY, WAIT, STOP }
}
