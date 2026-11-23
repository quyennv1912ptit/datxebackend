package com.nhom15.datxebackend.tracking

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.port.ActiveRideQueryPort
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.tracking.dto.DriverLocation
import com.nhom15.datxebackend.tracking.service.LocationService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.mockito.Mockito
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant

class LocationFreshnessTests {
    @Test fun `rapid fresh updates for one driver coalesce before querying PostgreSQL`() {
        val port = System.getenv("TEST_REDIS_PORT")?.toIntOrNull()
        assumeTrue(port != null && port != 6379, "Requires disposable Redis 7")
        val factory = LettuceConnectionFactory(RedisStandaloneConfiguration("127.0.0.1", port!!).apply { database = 11 })
        factory.afterPropertiesSet()
        try {
            val redis = StringRedisTemplate(factory).apply { afterPropertiesSet() }
            val driverId = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)
            val rides = Mockito.mock(ActiveRideQueryPort::class.java)
            val offers = Mockito.mock(DispatchOfferRepository::class.java)
            val service = LocationService(redis, Mockito.mock(DriverRepository::class.java), MatchingProperties(), offers, rides)
            val now = Instant.now()
            val first = GeoPoint(21.03, 105.84)
            val next = GeoPoint(21.04, 105.85)
            assertNotNull(service.saveDriverLocation(driverId, first, now.minusMillis(1)))
            assertNull(service.saveDriverLocation(driverId, next, now))
            assertEquals(first.lat.toString(), redis.opsForHash<String, String>().get(RedisKeys.driverLocation(driverId.toString()), "lat"))
            Mockito.verify(rides).findActiveRideForDriver(driverId)
        } finally {
            factory.destroy()
        }
    }
    @Test fun `old GPS cannot renew dispatch availability`() {
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        val drivers = Mockito.mock(DriverRepository::class.java)
        val accepted = LocationService(redis, drivers, MatchingProperties(), Mockito.mock(DispatchOfferRepository::class.java),
            Mockito.mock(ActiveRideQueryPort::class.java)).saveDriverLocation(7, GeoPoint(21.03, 105.84), Instant.now().minusSeconds(31))
        assertNull(accepted)
        Mockito.verifyNoInteractions(redis, drivers)
    }
    @Test fun `heartbeat TTL is bounded by the actual sample freshness`() {
        val redis = Mockito.mock(StringRedisTemplate::class.java, Mockito.RETURNS_DEEP_STUBS)
        val drivers = Mockito.mock(DriverRepository::class.java)
        LocationService(redis, drivers, MatchingProperties(), Mockito.mock(DispatchOfferRepository::class.java),
            Mockito.mock(ActiveRideQueryPort::class.java)).saveDriverLocation(7, GeoPoint(21.03, 105.84), Instant.now().minusSeconds(20))
        val sampleWrite = Mockito.mockingDetails(redis).invocations.single { it.method.name == "execute" }
        val ttlMillis = (sampleWrite.arguments.last() as String).toLong()
        assertTrue(ttlMillis in 1..10_000)
    }
    @Test fun `missing GPS timestamp cannot be filled with server receive time`() {
        val mapper = tools.jackson.module.kotlin.jacksonObjectMapper()
        assertThrows(Exception::class.java) { mapper.readValue("""{"location":{"lat":21.03,"lng":105.84},"accuracy":12} """, DriverLocation::class.java) }
    }
}
