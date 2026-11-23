package com.nhom15.datxebackend.tracking

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.config.properties.ServiceAreaProperties
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.matching.service.DispatchScripts
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.tracking.dto.DriverLocation
import com.nhom15.datxebackend.tracking.dto.DriverLocationPolicy
import com.nhom15.datxebackend.tracking.service.LocationService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.geo.Distance
import org.springframework.data.geo.GeoResult
import org.springframework.data.geo.GeoResults
import org.springframework.data.geo.Metrics
import org.springframework.data.geo.Point
import org.springframework.data.redis.connection.RedisGeoCommands
import org.springframework.data.redis.core.GeoOperations
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.ZSetOperations
import java.time.Instant

class DriverDispatchEligibilityTests {
    // GPS freshness is checked against the wall clock in saveDriverLocation/markDriverOnline,
    // so each test must use a live sample rather than a fixed instant.
    private fun freshSample() = Instant.now()

    private fun service(
        redis: StringRedisTemplate,
        drivers: DriverRepository = Mockito.mock(DriverRepository::class.java),
        area: ServiceAreaProperties = ServiceAreaProperties(),
    ) = LocationService(
        redis, drivers, MatchingProperties(),
        Mockito.mock(DispatchOfferRepository::class.java),
        Mockito.mock(RideRepository::class.java),
        area,
    )

    private fun onlineRedis(): StringRedisTemplate {
        // Deep stubs for Redis operations, but a realistic Long result for every script.
        // `execute(RedisScript<T>, ...)` is generic and erases to Object, so checking
        // `method.returnType == Long` never matched and SAVE_SAMPLE returned non-1L,
        // making saveDriverLocation exit before any accuracy gate ran.
        val redis = Mockito.mock(StringRedisTemplate::class.java, Mockito.withSettings().defaultAnswer { invocation ->
            if (invocation.method.name == "execute") {
                1L
            } else {
                Mockito.RETURNS_DEEP_STUBS.answer(invocation)
            }
        })
        Mockito.`when`(redis.opsForValue().get("driver:7:status")).thenReturn("ONLINE")
        return redis
    }

    private fun addAvailableCalls(redis: StringRedisTemplate) = Mockito.mockingDetails(redis).invocations
        .filter { it.method.name == "execute" && it.arguments.firstOrNull() === DispatchScripts.ADD_AVAILABLE }

    private class NearbyFixture(
        val redis: StringRedisTemplate,
        val zset: ZSetOperations<String, String>,
    )

    /**
     * One alive, Redis-ONLINE GEO hit with the requested accuracy field. The nested Redis
     * operation mocks need their own answers: deep stubs on StringRedisTemplate do not inherit
     * the template's default answer, so seeding radius/multiGet there silently returns empty.
     */
    @Suppress("UNCHECKED_CAST")
    private fun nearbyRedis(
        accuracy: String? = null,
        failAccuracyRead: Boolean = false,
        failStatusRead: Boolean = false,
    ): NearbyFixture {
        val results = GeoResults(
            listOf(
                GeoResult(
                    RedisGeoCommands.GeoLocation("7", Point(105.84, 21.03)),
                    Distance(0.5, Metrics.KILOMETERS),
                ),
            ),
        )
        val geo = Mockito.mock(GeoOperations::class.java) { invocation ->
            if (invocation.method.name == "radius") results else null
        }
        val value = Mockito.mock(ValueOperations::class.java) { invocation ->
            if (invocation.method.name == "multiGet") {
                @Suppress("UNCHECKED_CAST")
                val keys = invocation.getArgument<List<String>>(0)
                if (keys.firstOrNull()?.startsWith("driver:alive:") == true) {
                    listOf("1")
                } else if (failStatusRead) {
                    null
                } else {
                    listOf("ONLINE")
                }
            } else {
                null
            }
        }
        val hash = Mockito.mock(HashOperations::class.java) { invocation ->
            if (invocation.method.name == "get") {
                if (failAccuracyRead) throw IllegalStateException("HGET failed") else accuracy
            } else {
                null
            }
        }
        val zset = Mockito.mock(ZSetOperations::class.java) as ZSetOperations<String, String>
        val redis = Mockito.mock(StringRedisTemplate::class.java, Mockito.withSettings().defaultAnswer { invocation ->
            when (invocation.method.name) {
                "opsForGeo" -> geo
                "opsForValue" -> value
                "opsForHash" -> hash
                "opsForZSet" -> zset
                "execute" -> 1L
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        return NearbyFixture(redis, zset)
    }

    private fun onlineDriver(status: DriverStatus = DriverStatus.ONLINE): DriverRepository =
        Mockito.mock(DriverRepository::class.java).also { repository ->
            Mockito.`when`(repository.findAllById(listOf(7L)))
                .thenReturn(listOf(Driver().apply { id = 7; this.status = status }))
        }

    private fun nearby(redis: StringRedisTemplate, drivers: DriverRepository) =
        service(redis, drivers).findNearbyDrivers(GeoPoint(21.03, 105.84), 5.0, 10)

    @Test
    fun `coarse GPS is stored but never enters the nearby-driver index`() {
        val redis = onlineRedis()
        val accepted = service(redis).saveDriverLocation(7, GeoPoint(21.03, 105.84), freshSample(), 8_000.0)

        assertTrue(accepted)
        val sampleWrite = Mockito.mockingDetails(redis).invocations.first {
            it.method.name == "execute" && it.arguments.firstOrNull() !== DispatchScripts.ADD_AVAILABLE
        }
        assertTrue(sampleWrite.arguments.any { it == "8000.0" }, "accuracy must be persisted with the fix")
        assertTrue(addAvailableCalls(redis).isEmpty())
    }

    @Test
    fun `accurate and legacy accuracy-free fixes remain dispatchable`() {
        for (accuracy in listOf(null, 0.0, 12.0, DriverLocationPolicy.MAX_DISPATCH_ACCURACY_METERS)) {
            val redis = onlineRedis()
            assertTrue(service(redis).saveDriverLocation(7, GeoPoint(21.03, 105.84), freshSample(), accuracy))
            assertEquals(1, addAvailableCalls(redis).size, "accuracy=$accuracy should stay dispatchable")
        }
    }

    @Test
    fun `markDriverOnline rechecks stored accuracy before re-adding to GEO`() {
        val redis = onlineRedis()
        val sampledAt = freshSample()
        Mockito.`when`(redis.hasKey("driver:alive:7")).thenReturn(true)
        Mockito.`when`(redis.opsForHash<String, String>().entries("driver:7:location")).thenReturn(
            mapOf(
                "lat" to "21.03", "lng" to "105.84",
                "sampledAt" to sampledAt.toString(), "sampledAtMs" to sampledAt.toEpochMilli().toString(),
                "accuracy" to "9000.0",
            ),
        )

        service(redis).markDriverOnline(7)
        assertTrue(addAvailableCalls(redis).isEmpty())
    }

    @Test
    fun `driver GPS outside the configured service area is rejected before any Redis write`() {
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        val area = ServiceAreaProperties(centerLat = 10.0, centerLng = 106.0, radiusKm = 1.0)
        val error = assertThrows(AppException::class.java) {
            service(redis, area = area).saveDriverLocation(7, GeoPoint(21.03, 105.84))
        }
        assertEquals(ErrorCode.OUT_OF_SERVICE_AREA, error.errorCode)
        Mockito.verifyNoInteractions(redis)
    }

    @Test
    fun `policy caps future skew while keeping coarse fixes valid for storage`() {
        val now = Instant.parse("2026-11-22T10:00:00Z")
        val coarse = DriverLocation(GeoPoint(21.03, 105.84), now, 9_000.0)
        assertTrue(DriverLocationPolicy.valid(coarse, now))
        assertFalse(DriverLocationPolicy.dispatchEligible(coarse.accuracy))
        assertTrue(DriverLocationPolicy.valid(coarse.copy(timestamp = now.plusSeconds(2)), now))
        assertFalse(DriverLocationPolicy.valid(coarse.copy(timestamp = now.plusSeconds(5)), now))
        assertFalse(DriverLocationPolicy.dispatchEligible(DriverLocationPolicy.MAX_DISPATCH_ACCURACY_METERS + 1))
    }

    @Test
    fun `nearby query drops a driver whose DB status is no longer online`() {
        // Redis keys outlive the after-commit cleanup when it fails: DB is authoritative.
        val fixture = nearbyRedis()
        assertTrue(nearby(fixture.redis, onlineDriver(DriverStatus.OFFLINE)).isEmpty())
        Mockito.verify(fixture.zset).remove(RedisKeys.GEO_KEY, "7")
    }

    @Test
    fun `nearby query keeps an online driver with legacy accuracy`() {
        val fixture = nearbyRedis()
        val result = nearby(fixture.redis, onlineDriver())
        assertEquals(1, result.size)
        assertEquals(7L, result.single().driverId)
        Mockito.verify(fixture.zset, Mockito.never()).remove(RedisKeys.GEO_KEY, "7")
    }

    @Test
    fun `nearby query drops coarse accuracy re-added by dispatch scripts`() {
        val fixture = nearbyRedis("8000.0")
        assertTrue(nearby(fixture.redis, onlineDriver()).isEmpty())
        Mockito.verify(fixture.zset).remove(RedisKeys.GEO_KEY, "7")
    }

    @Test
    fun `nearby query evicts nobody when the whole status batch read fails`() {
        val fixture = nearbyRedis(failStatusRead = true)
        assertTrue(nearby(fixture.redis, onlineDriver()).isEmpty())
        // A null batch is a Redis error, not evidence that every driver went offline.
        Mockito.verify(fixture.zset, Mockito.never()).remove(RedisKeys.GEO_KEY, "7")
    }

    @Test
    fun `nearby query skips but does not evict a driver when the accuracy read errors`() {
        val fixture = nearbyRedis(failAccuracyRead = true)
        assertTrue(nearby(fixture.redis, onlineDriver()).isEmpty())
        // A transient HGET failure is not evidence of a blocked driver; keep the index entry
        // so the next successful location sample or heartbeat remains dispatchable.
        Mockito.verify(fixture.zset, Mockito.never()).remove(RedisKeys.GEO_KEY, "7")
    }
}
