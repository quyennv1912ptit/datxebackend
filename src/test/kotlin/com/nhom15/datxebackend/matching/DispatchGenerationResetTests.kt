package com.nhom15.datxebackend.matching

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.port.NearbyDriverQueryPort
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.config.properties.ScheduledRideProperties
import com.nhom15.datxebackend.matching.service.DispatchScripts
import com.nhom15.datxebackend.matching.service.DriverSelectionService
import com.nhom15.datxebackend.matching.service.OfferReleaseService
import com.nhom15.datxebackend.matching.service.RideDispatchService
import com.nhom15.datxebackend.matching.service.RideOfferBuilder
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.any
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Optional

class DispatchGenerationResetTests {
    @Test
    fun `dispatch passes the candidates key to initialize search`() {
        val rides = mock(RideRepository::class.java)
        val redis = mock(StringRedisTemplate::class.java, RETURNS_DEEP_STUBS)
        val current = Ride(
            customerId = 1, vehicleId = 2, type = RideType.INSTANT,
            pickup = Location(GeoPoint(21.03, 105.84), "Đón"),
            dropoff = Location(GeoPoint(21.04, 105.85), "Đến"),
            vehicleType = VehicleType.CAR, transmission = Transmission.AUTO,
            fareEstimate = 30000, paymentMethod = PaymentMethod.CASH,
            status = RideStatus.SEARCHING,
        ).apply {
            id = 42
            searchExpiresAt = Instant.now().plusSeconds(90)
        }
        `when`(rides.findById(42)).thenReturn(Optional.of(current))
        `when`(redis.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration::class.java))).thenReturn(true)
        // Redis already shows the assignment made by a newer tick: keep the test at the INITIALIZE_SEARCH call.
        `when`(redis.opsForHash<String, String>().get(RedisKeys.rideState("42"), "status")).thenReturn("DRIVER_ASSIGNED")
        val service = RideDispatchService(
            nearbyDriverQueryPort = mock(NearbyDriverQueryPort::class.java),
            selectionService = mock(DriverSelectionService::class.java),
            matchingProperties = MatchingProperties(),
            redisTemplate = redis,
            publisher = mock(ApplicationEventPublisher::class.java),
            rideOfferBuilder = mock(RideOfferBuilder::class.java),
            notificationPort = mock(NotificationPort::class.java),
            releaseService = mock(OfferReleaseService::class.java),
            rideRepository = rides,
            outbox = mock(RideDispatchOutboxRepository::class.java),
            offers = mock(DispatchOfferRepository::class.java),
            scheduled = ScheduledRideProperties(),
            clock = Clock.systemUTC(),
        )

        service.dispatchRide(42)

        val initialize = mockingDetails(redis).invocations.first { invocation ->
            invocation.arguments.firstOrNull() === DispatchScripts.INITIALIZE_SEARCH
        }
        assertEquals(
            listOf(RedisKeys.rideState("42"), RedisKeys.rideCandidates("42")),
            initialize.arguments[1],
        )
    }

    @Test
    fun `initialize search script deletes candidates only after the generation guard`() {
        val script = DispatchScripts.INITIALIZE_SEARCH.scriptAsString
        val guard = script.indexOf("then return 0 end")
        val deleteCandidates = script.indexOf("redis.call('DEL', KEYS[2])")

        assertTrue(guard >= 0, "Thiếu điều kiện replay idempotent theo generation")
        assertTrue(deleteCandidates > guard, "KEYS[2] chỉ được xoá sau khi script xác nhận reset generation")
        assertTrue(script.contains("'generation', ARGV[8]"), "ARGV[8] vẫn là generation")
    }
}
