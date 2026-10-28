package com.nhom15.datxebackend.routing.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.routing.api.DrivingRouteProvider
import com.nhom15.datxebackend.routing.model.PolylineCodec
import com.nhom15.datxebackend.routing.model.SavedRideRoute
import com.nhom15.datxebackend.routing.repository.RideRouteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Service
class RideRouteService(private val routes: DrivingRouteProvider, private val repository: RideRouteRepository) {
    @Transactional
    fun resolve(customerId: Long, vehicleId: Long, vehicleType: VehicleType, pickup: GeoPoint, dropoff: GeoPoint, requestedId: String? = null): SavedRideRoute {
        val key = MessageDigest.getInstance("SHA-256").digest("$customerId|$vehicleId|$vehicleType|${pickup.lat},${pickup.lng}|${dropoff.lat},${dropoff.lng}".toByteArray()).joinToString("") { "%02x".format(it) }
        repository.lock(key)
        val saved = repository.find(key)
        val now = Instant.now()
        if (requestedId != null && (saved == null || saved.id != requestedId || saved.expiresAt?.isAfter(now) != true)) throw ConflictException(message = "Lộ trình đã hết hạn hoặc không khớp điểm đón/đến. Hãy lấy báo giá mới trước khi đặt chuyến.")
        if (saved != null && saved.expiresAt?.isAfter(now) == true) return saved
        val route = routes.findRoute(pickup, dropoff, vehicleType, true)
        if (route.coordinates.size < 2 || !route.distanceMeters.isFinite() || route.distanceMeters <= 0 || !route.durationSeconds.isFinite() || route.durationSeconds <= 0) throw AppException(ErrorCode.ROUTE_UNAVAILABLE)
        val snapshot = SavedRideRoute(UUID.randomUUID().toString(), PolylineCodec.encode(route.coordinates), route.distanceMeters, route.durationSeconds, route.provider, vehicleType, route.limitation, now.plusSeconds(1200))
        repository.save(key, snapshot)
        return snapshot
    }
}
