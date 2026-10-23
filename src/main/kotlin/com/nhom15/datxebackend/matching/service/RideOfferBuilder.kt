package com.nhom15.datxebackend.matching.service

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.util.GeoUtils.distanceKm
import com.nhom15.datxebackend.notification.dto.CustomerBrief
import com.nhom15.datxebackend.notification.dto.RideOfferPayload
import com.nhom15.datxebackend.notification.dto.VehicleInfo
import com.nhom15.datxebackend.notification.dto.toDto
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.routing.service.MapService
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Component
class RideOfferBuilder(
    private val rideRepository: RideRepository,
    private val mapService: MapService,
    private val redisTemplate: StringRedisTemplate,
    private val customerRepository: CustomerRepository
) {
    fun build(rideId: Long, driverId: Long, expiresAt: Long): RideOfferPayload {
        val ride = rideRepository.findByIdOrNull(rideId)
            ?: throw NotFoundException(message = "Không tìm thấy chuyến $rideId")

        val customer = customerRepository.findByIdOrNull(ride.customerId)

        val route = mapService.getDistanceAndDuration(
            origin = "${ride.pickup.geoPoint.lng},${ride.pickup.geoPoint.lat}",
            destination = "${ride.dropoff.geoPoint.lng},${ride.dropoff.geoPoint.lat}"
        )

        val location = redisTemplate.opsForHash<String, String>()
            .entries(RedisKeys.driverLocation(driverId.toString()))
        val storedLat = location["lat"]?.toDoubleOrNull()
        val storedLng = location["lng"]?.toDoubleOrNull()
        val driverPoint = redisTemplate.opsForGeo()
            .position(RedisKeys.GEO_KEY, driverId.toString())
            ?.firstOrNull()
        val driverLocation = if (storedLat != null && storedLng != null) {
            GeoPoint(storedLat, storedLng)
        } else {
            driverPoint?.let { GeoPoint(it.y, it.x) }
                ?: throw NotFoundException(message = "Không có vị trí của tài xế $driverId")
        }

        val toPickupKm = distanceKm(driverLocation, ride.pickup.geoPoint)
        val straightKm = distanceKm(ride.pickup.geoPoint, ride.dropoff.geoPoint)

        val routeKm = route.distance / 1000.0
        val tripKm = ride.distanceKm
            ?: routeKm.takeIf { it > 0 }
            ?: (straightKm * ROAD_FACTOR)
        val tripMin = if (route.duration > 0) ceil(route.duration / 60.0).toInt() else minutesFor(tripKm)

        return RideOfferPayload(
            rideId = ride.id,
            expiresAt = expiresAt,
            pickup = ride.pickup.toDto(),
            dropoff = ride.dropoff.toDto(),
            distanceToPickupKm = round1(toPickupKm),
            etaToPickupMin = minutesFor(toPickupKm),
            tripDistanceKm = round1(tripKm),
            estDurationMin = tripMin,
            fareEstimate = ride.fareEstimate,
            driverEarning = (ride.fareEstimate * DRIVER_SHARE).roundToLong(),
            paymentMethod = ride.paymentMethod,
            vehicle = VehicleInfo(ride.vehicleType, ride.transmission),
            customer = CustomerBrief(
                displayName = customer?.fullName?.substringAfterLast(' ')?.takeIf { it.isNotBlank() } ?: "Khách",
                rating = customer?.averageRating ?: 5.0
            ),
            note = ride.note
        )
    }

    private fun minutesFor(km: Double): Int =
        ceil(km / AVG_SPEED_KMH * 60).toInt().coerceAtLeast(1)

    private fun round1(x: Double): Double = (x * 10).roundToInt() / 10.0

    private companion object {
        const val AVG_SPEED_KMH = 25.0
        const val ROAD_FACTOR = 1.3
        const val DRIVER_SHARE = 0.8
    }
}
