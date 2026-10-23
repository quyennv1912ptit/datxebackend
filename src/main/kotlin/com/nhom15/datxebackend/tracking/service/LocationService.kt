package com.nhom15.datxebackend.tracking.service

import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.port.NearbyDriverQueryPort
import com.nhom15.datxebackend.common.port.NearbyDriver
import com.nhom15.datxebackend.config.properties.MatchingProperties
import org.springframework.data.geo.Circle
import org.springframework.data.geo.Distance
import org.springframework.data.geo.Metrics
import org.springframework.data.geo.Point
import org.springframework.data.redis.connection.RedisGeoCommands
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class LocationService(
    private val redisTemplate: StringRedisTemplate,
    private val driverRepository: DriverRepository,
    private val matchingProperties: MatchingProperties,
) : NearbyDriverQueryPort {

    companion object {
        private const val MAX_LAT = 85.05112878
    }

    /** Lưu/cập nhật vị trí driver và gia hạn heartbeat. */
    fun saveDriverLocation(driverId: Long, location: GeoPoint) {
        validate(location)
        val id = driverId.toString()
        redisTemplate.opsForHash<String, String>().putAll(
            RedisKeys.driverLocation(id),
            mapOf("lat" to location.lat.toString(), "lng" to location.lng.toString()),
        )
        redisTemplate.opsForValue().set(RedisKeys.driverAlive(id), "1", matchingProperties.driverAliveTtl)

        // Driver đã bật nhận chuyến ở DB nhưng Redis vừa được khởi động lại.
        val statusKey = RedisKeys.driverStatus(id)
        if (redisTemplate.hasKey(statusKey) != true) {
            val isOnline = driverRepository.findById(driverId)
                .map { it.status == DriverStatus.ONLINE }
                .orElse(false)
            if (isOnline) redisTemplate.opsForValue().set(statusKey, "ONLINE")
        }

        val unavailable = redisTemplate.hasKey(RedisKeys.driverLock(id)) == true ||
                redisTemplate.hasKey(RedisKeys.driverCurrentRide(id)) == true
        if (unavailable || redisTemplate.opsForValue().get(statusKey) != "ONLINE") {
            redisTemplate.opsForGeo().remove(RedisKeys.GEO_KEY, id)
            return
        }

        redisTemplate.opsForGeo().add(RedisKeys.GEO_KEY, Point(location.lng, location.lat), id)
    }

    /** Đưa tài xế đã bật nhận chuyến vào GEO khi đã có vị trí và heartbeat mới. */
    fun markDriverOnline(driverId: Long) {
        val id = driverId.toString()
        val unavailable = redisTemplate.hasKey(RedisKeys.driverLock(id)) == true ||
                redisTemplate.hasKey(RedisKeys.driverCurrentRide(id)) == true
        if (unavailable) return

        redisTemplate.opsForValue().set(RedisKeys.driverStatus(id), "ONLINE")
        if (redisTemplate.hasKey(RedisKeys.driverAlive(id)) != true) return

        val location = redisTemplate.opsForHash<String, String>().entries(RedisKeys.driverLocation(id))
        val lat = location["lat"]?.toDoubleOrNull() ?: return
        val lng = location["lng"]?.toDoubleOrNull() ?: return
        redisTemplate.opsForGeo().add(RedisKeys.GEO_KEY, Point(lng, lat), id)
    }

    /** Xóa driver khỏi tập khả dụng (tắt app, nhận chuyến, đang trong chuyến...). */
    fun removeDriver(driverId: Long) {
        val id = driverId.toString()
        redisTemplate.opsForGeo().remove(RedisKeys.GEO_KEY, id)
        redisTemplate.delete(RedisKeys.driverAlive(id))
        redisTemplate.opsForValue().set(RedisKeys.driverStatus(id), "OFFLINE")
    }

    /** Tìm driver còn online trong bán kính [radiusKm], gần nhất trước. */
    override fun findNearbyDrivers(center: GeoPoint, radiusKm: Double, maxResults: Long): List<NearbyDriver> {
        validate(center)
        require(radiusKm > 0) { "radiusKm phải lớn hơn 0" }
        require(maxResults > 0) { "maxResults phải lớn hơn 0" }

        val searchArea = Circle(Point(center.lng, center.lat), Distance(radiusKm, Metrics.KILOMETERS))
        val args = RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
            .includeDistance()     // Trả thêm khoảng cách
            .includeCoordinates()  // Trả thêm tọa độ
            .sortAscending()       // Gần nhất trước
            .limit(maxResults)

        val found = redisTemplate.opsForGeo()
            .radius(RedisKeys.GEO_KEY, searchArea, args)
            ?.content
            ?.mapNotNull { result ->
                // Member trong Redis là String, bỏ qua phần tử không phải số
                result.content.name.toLongOrNull()?.let { id -> id to result }
            }
            ?: return emptyList()
        if (found.isEmpty()) return emptyList()

        // Lọc driver đã mất kết nối (heartbeat hết hạn)
        val alive = redisTemplate.opsForValue()
            .multiGet(found.map { (id, _) -> RedisKeys.driverAlive(id.toString()) })
            ?: return emptyList()

        return found
            .filterIndexed { i, _ -> alive.getOrNull(i) != null }
            .map { (id, result) ->
                NearbyDriver(
                    driverId = id,
                    distanceKm = result.distance.value,
                    location = GeoPoint(lat = result.content.point.y, lng = result.content.point.x)
                )
            }
    }

    /** Dọn driver hết heartbeat khỏi GEO set. Cần @EnableScheduling. */
    @Scheduled(fixedDelay = 60_000)
    fun cleanupStaleDrivers() {
        val ids = redisTemplate.opsForZSet().range(RedisKeys.GEO_KEY, 0, -1)?.toList() ?: return
        if (ids.isEmpty()) return

        val alive = redisTemplate.opsForValue()
            .multiGet(ids.map { RedisKeys.driverAlive(it) })
            ?: return
        val stale = ids.filterIndexed { i, _ -> alive.getOrNull(i) == null }
        if (stale.isNotEmpty()) {
            redisTemplate.opsForGeo().remove(RedisKeys.GEO_KEY, *stale.toTypedArray())
        }
    }

    private fun validate(p: GeoPoint) {
        require(p.lng in -180.0..180.0) { "lng phải trong khoảng -180..180" }
        require(p.lat in -MAX_LAT..MAX_LAT) { "lat phải trong khoảng -85.05..85.05" }
    }
}
