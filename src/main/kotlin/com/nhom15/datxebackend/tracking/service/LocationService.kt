package com.nhom15.datxebackend.tracking.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.util.GeoUtils.distanceKm
import com.nhom15.datxebackend.config.properties.ServiceAreaProperties
import com.nhom15.datxebackend.matching.service.DispatchScripts
import com.nhom15.datxebackend.tracking.dto.DriverLocationPolicy
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import java.time.Duration
import java.time.Instant
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.port.NearbyDriverQueryPort
import com.nhom15.datxebackend.common.port.NearbyDriver
import com.nhom15.datxebackend.config.properties.MatchingProperties
import org.slf4j.LoggerFactory
import org.springframework.data.geo.Circle
import org.springframework.data.geo.Distance
import org.springframework.data.geo.Metrics
import org.springframework.data.geo.Point
import org.springframework.data.redis.connection.RedisGeoCommands
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class LocationService(
    private val redisTemplate: StringRedisTemplate,
    private val driverRepository: DriverRepository,
    private val matchingProperties: MatchingProperties,
    private val offers: DispatchOfferRepository,
    private val rides: RideRepository,
    // Default keeps standalone/unit construction working while Spring injects the configured bean.
    private val serviceArea: ServiceAreaProperties = ServiceAreaProperties(),
) : NearbyDriverQueryPort {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val MAX_LAT = 85.05112878

        // ARGV: [1] accuracy (empty = legacy client), [2] driverId, [3] lat, [4] lng,
        // [5] sampledAt, [6] sampledAtMs, [7] alive TTL ms. TTL stays last for existing callers/tests.
        private val SAVE_SAMPLE = DefaultRedisScript<Long>(
            """
            local incoming = tonumber(ARGV[6])
            local saved = tonumber(redis.call('HGET', KEYS[1], 'sampledAtMs'))
            if saved and incoming <= saved then return 0 end
            redis.call('HSET', KEYS[1], 'lat', ARGV[3], 'lng', ARGV[4], 'sampledAt', ARGV[5], 'sampledAtMs', ARGV[6], 'accuracy', ARGV[1])
            redis.call('PSETEX', KEYS[2], ARGV[7], '1')
            redis.call('ZADD', KEYS[3], incoming, ARGV[2])
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        private val REMOVE_IF_STALE = DefaultRedisScript<Long>(
            """
            local removed = 0
            for i = 1, #ARGV do
                if redis.call('EXISTS', KEYS[i + 1]) == 0 then
                    removed = removed + redis.call('ZREM', KEYS[1], ARGV[i])
                end
            end
            return removed
            """.trimIndent(),
            Long::class.java,
        )
    }

    /** Lưu/cập nhật vị trí driver và gia hạn heartbeat. */
    fun saveDriverLocation(
        driverId: Long,
        location: GeoPoint,
        sampledAt: Instant = Instant.now(),
        accuracy: Double? = null,
    ): Boolean {
        validate(location)
        validateDriverServiceArea(location)
        val id = driverId.toString()
        val dispatchEligible = DriverLocationPolicy.dispatchEligible(accuracy)
        // An old GPS sample must not renew dispatch eligibility merely because it was resent.
        val remainingFreshness = Duration.between(Instant.now(), sampledAt.plus(matchingProperties.driverAliveTtl))
        val ttlMillis = minOf(matchingProperties.driverAliveTtl, remainingFreshness).toMillis()
        if (ttlMillis <= 0) return false
        val sampledAtMillis = sampledAt.toEpochMilli().toString()
        // REST and STOMP may arrive out of order. The stored fix and its expiry advance together.
        if (redisTemplate.execute(
                SAVE_SAMPLE,
                listOf(RedisKeys.driverLocation(id), RedisKeys.driverAlive(id), RedisKeys.DRIVERS_LASTSEEN),
                accuracy?.toString().orEmpty(), id, location.lat.toString(), location.lng.toString(),
                sampledAt.toString(), sampledAtMillis, ttlMillis.toString(),
            ) != 1L) return false

        // A coarse fix is persisted for live tracking, but must never make the driver discoverable.
        if (!dispatchEligible) {
            redisTemplate.opsForZSet().remove(RedisKeys.GEO_KEY, id)
            return true
        }

        // PostgreSQL prevents a Redis restart from making an assigned driver available again.
        val activeRide = rides.findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(driverId,
            ACTIVE_RIDE_STATUSES)
        if (activeRide != null) {
            redisTemplate.opsForGeo().remove(RedisKeys.GEO_KEY, id)
            return true
        }
        if (offers.driverHasOffer(driverId)) {
            redisTemplate.opsForGeo().remove(RedisKeys.GEO_KEY, id)
            return true
        }

        // Driver đã bật nhận chuyến ở DB nhưng Redis vừa được khởi động lại, hoặc Redis đã
        // tự đặt OFFLINE vì heartbeat trước đó hết hạn. App vẫn gửi vị trí nghĩa là tài xế
        // vẫn online, nên khôi phục trạng thái thay vì bỏ qua mọi cập nhật sau đó.
        val unavailable = redisTemplate.hasKey(RedisKeys.driverLock(id)) == true ||
                redisTemplate.hasKey(RedisKeys.driverCurrentRide(id)) == true
        val statusKey = RedisKeys.driverStatus(id)
        val currentStatus = redisTemplate.opsForValue().get(statusKey)
        if (!unavailable && (currentStatus == null || currentStatus == "OFFLINE")) {
            val isOnline = driverRepository.findById(driverId)
                .map { it.status == DriverStatus.ONLINE }
                .orElse(false)
            if (isOnline) {
                redisTemplate.opsForValue().set(statusKey, "ONLINE")
                if (currentStatus == "OFFLINE") {
                    log.info("Tài xế {} gửi lại vị trí, khôi phục trạng thái ONLINE", driverId)
                }
            }
        }

        if (unavailable || redisTemplate.opsForValue().get(statusKey) != "ONLINE") {
            redisTemplate.opsForGeo().remove(RedisKeys.GEO_KEY, id)
            return true
        }

        redisTemplate.execute(DispatchScripts.ADD_AVAILABLE,
            listOf(RedisKeys.GEO_KEY), id, location.lng.toString(), location.lat.toString(), sampledAtMillis)
        return true
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
        val sampledAt = runCatching { Instant.parse(location["sampledAt"]) }.getOrNull() ?: return
        if (sampledAt.isBefore(Instant.now().minus(matchingProperties.driverAliveTtl))) return
        val lat = location["lat"]?.toDoubleOrNull() ?: return
        val lng = location["lng"]?.toDoubleOrNull() ?: return
        if (!DriverLocationPolicy.dispatchEligible(location["accuracy"])) {
            redisTemplate.opsForZSet().remove(RedisKeys.GEO_KEY, id)
            return
        }
        redisTemplate.execute(DispatchScripts.ADD_AVAILABLE,
            listOf(RedisKeys.GEO_KEY), id, lng.toString(), lat.toString(), location["sampledAtMs"].orEmpty())
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

        // A null batch result is a transient Redis error, not "everyone is offline".
        // Treating it as empty would evict every candidate from the GEO index.
        val statuses = redisTemplate.opsForValue()
            .multiGet(found.map { (id, _) -> RedisKeys.driverStatus(id.toString()) })
            ?: return emptyList()
        val stale = found.filterIndexed { i, _ -> alive.getOrNull(i) == null }.map { it.first.toString() }
        removeIfStillStale(stale)

        // Redis status/alive keys can outlive a block, a deletion or an OFFLINE toggle whose
        // after-commit cleanup failed. The DB row is authoritative: only ONLINE drivers are dispatchable.
        val dbEligibleIds = driverRepository.findAllById(found.map { (id, _) -> id })
            .filter { it.status == DriverStatus.ONLINE && it.deletedAt == null }
            .map { it.id }
            .toSet()

        val blockedFromGeo = mutableListOf<String>()
        return found
            .filterIndexed { i, (id, _) ->
                // Stale heartbeats were already removed by the rechecking Lua script above.
                if (alive.getOrNull(i) == null) return@filterIndexed false
                if (statuses.getOrNull(i) != "ONLINE" || id !in dbEligibleIds) {
                    blockedFromGeo += id.toString()
                    return@filterIndexed false
                }
                // A failed HGET must not be mistaken for a legacy sample with no accuracy:
                // legacy is a genuine null result, while an error means we cannot prove eligibility.
                var accuracyReadFailed = false
                val rawAccuracy = try {
                    redisTemplate.opsForHash<String, String>()
                        .get(RedisKeys.driverLocation(id.toString()), "accuracy")
                } catch (error: Exception) {
                    accuracyReadFailed = true
                    log.warn("Không đọc được độ chính xác của tài xế {} khi tìm chuyến", id, error)
                    null
                }
                if (accuracyReadFailed) {
                    // We cannot prove eligibility, so skip this round, but keep the index entry:
                    // a transient Redis read failure is not evidence that the driver is blocked.
                    false
                } else if (DriverLocationPolicy.dispatchEligible(rawAccuracy)) {
                    true
                } else {
                    // Defensive: other Redis scripts may re-add a driver from its stored coordinates.
                    blockedFromGeo += id.toString()
                    false
                }
            }
            .also { removeFromGeo(blockedFromGeo) }
            .map { (id, result) ->
                NearbyDriver(
                    driverId = id,
                    distanceKm = result.distance.value,
                    location = GeoPoint(lat = result.content.point.y, lng = result.content.point.x)
                )
            }
    }

    /** Dọn driver hết heartbeat khỏi GEO set. Cần @EnableScheduling. */
    @Scheduled(fixedDelayString = "\${app.matching.cleanup-interval-ms:10000}")
    fun cleanupStaleDrivers() {
        val ids = redisTemplate.opsForZSet().range(RedisKeys.GEO_KEY, 0, -1)?.toList() ?: return
        if (ids.isEmpty()) return

        val alive = redisTemplate.opsForValue()
            .multiGet(ids.map { RedisKeys.driverAlive(it) })
            ?: return
        val stale = ids.filterIndexed { i, _ -> alive.getOrNull(i) == null }
        removeIfStillStale(stale)
    }

    private fun removeIfStillStale(ids: List<String>) {
        if (ids.isEmpty()) return
        // A heartbeat can arrive between multiGet and cleanup. Recheck under
        // the same Redis operation that removes the GEO entry.
        for (batch in ids.chunked(100)) {
            redisTemplate.execute(
                REMOVE_IF_STALE,
                listOf(RedisKeys.GEO_KEY) + batch.map(RedisKeys::driverAlive),
                *batch.toTypedArray(),
            )
        }
    }

    /** Blocked/deleted/coarse drivers must leave the index even while their heartbeat is alive. */
    private fun removeFromGeo(ids: List<String>) {
        if (ids.isEmpty()) return
        for (batch in ids.distinct().chunked(100)) {
            redisTemplate.opsForZSet().remove(RedisKeys.GEO_KEY, *batch.toTypedArray())
        }
    }

    /** Driver GPS obeys the same operational area as ride pins before entering dispatch. */
    private fun validateDriverServiceArea(point: GeoPoint) {
        if (!serviceArea.enabled) return
        val center = GeoPoint(serviceArea.centerLat, serviceArea.centerLng)
        if (distanceKm(center, point) > serviceArea.radiusKm) {
            throw AppException(
                ErrorCode.OUT_OF_SERVICE_AREA,
                "Vị trí tài xế nằm ngoài vùng phục vụ",
                ErrorCode.OUT_OF_SERVICE_AREA.httpStatus,
            )
        }
    }

    private fun validate(p: GeoPoint) {
        require(p.lng in -180.0..180.0) { "lng phải trong khoảng -180..180" }
        require(p.lat in -MAX_LAT..MAX_LAT) { "lat phải trong khoảng -85.05..85.05" }
    }
}
