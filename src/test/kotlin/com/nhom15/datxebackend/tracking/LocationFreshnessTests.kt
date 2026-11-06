package com.nhom15.datxebackend.tracking

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.tracking.dto.DriverLocation
import com.nhom15.datxebackend.tracking.service.LocationService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant

class LocationFreshnessTests {
    @Test fun `old GPS cannot renew dispatch availability`() {
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        val drivers = Mockito.mock(DriverRepository::class.java)
        val accepted = LocationService(redis, drivers, MatchingProperties(), Mockito.mock(com.nhom15.datxebackend.ride.repository.DispatchOfferRepository::class.java), Mockito.mock(com.nhom15.datxebackend.ride.repository.RideRepository::class.java)).saveDriverLocation(7, GeoPoint(21.03, 105.84), Instant.now().minusSeconds(31))
        assertFalse(accepted)
        Mockito.verifyNoInteractions(redis, drivers)
    }
    @Test fun `heartbeat TTL is bounded by the actual sample freshness`() {
        val redis = Mockito.mock(StringRedisTemplate::class.java, Mockito.RETURNS_DEEP_STUBS)
        val drivers = Mockito.mock(DriverRepository::class.java)
        LocationService(redis, drivers, MatchingProperties(), Mockito.mock(com.nhom15.datxebackend.ride.repository.DispatchOfferRepository::class.java), Mockito.mock(com.nhom15.datxebackend.ride.repository.RideRepository::class.java)).saveDriverLocation(7, GeoPoint(21.03, 105.84), Instant.now().minusSeconds(20))
        val sampleWrite = Mockito.mockingDetails(redis).invocations.single { it.method.name == "execute" }
        val ttlMillis = (sampleWrite.arguments.last() as String).toLong()
        assertTrue(ttlMillis in 1..10_000)
    }
    @Test fun `missing GPS timestamp cannot be filled with server receive time`() {
        val mapper = tools.jackson.module.kotlin.jacksonObjectMapper()
        assertThrows(Exception::class.java) { mapper.readValue("""{"location":{"lat":21.03,"lng":105.84},"accuracy":12} """, DriverLocation::class.java) }
    }
}
