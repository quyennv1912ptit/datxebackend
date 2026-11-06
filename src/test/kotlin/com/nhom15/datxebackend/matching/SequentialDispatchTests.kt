package com.nhom15.datxebackend.matching

import com.nhom15.datxebackend.common.domain.*
import com.nhom15.datxebackend.common.enums.*
import com.nhom15.datxebackend.common.event.EventPublisher
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.port.NearbyDriverQueryPort
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.matching.service.*
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.dto.RideOfferPayload
import com.nhom15.datxebackend.common.port.RideAssignmentPort
import com.nhom15.datxebackend.ride.entity.*
import com.nhom15.datxebackend.ride.repository.*
import com.nhom15.datxebackend.ride.service.state.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.util.Optional

class SequentialDispatchTests {
    private fun ride(status: RideStatus = RideStatus.SEARCHING) = Ride(
        customerId=1, vehicleId=2, type=RideType.INSTANT, pickup=Location(GeoPoint(21.03,105.84),"Đón"),
        dropoff=Location(GeoPoint(21.04,105.85),"Đến"), vehicleType=VehicleType.CAR,
        transmission=Transmission.AUTO, fareEstimate=30000, paymentMethod=PaymentMethod.CASH,
        status=status,
    ).apply { id=42; searchExpiresAt=Instant.now().plusSeconds(90) }

    @Test fun `offer defaults allow twenty-five seconds on the client and preserve the server grace`() {
        val properties = MatchingProperties()
        assertEquals(27L, properties.offerTimeout.seconds)
        assertEquals(25L, properties.offerTimeout.minus(properties.clientOfferGrace).seconds)
    }

    @Test fun `empty scans expand radius then rescan max radius until absolute deadline`() {
        val rides=mock(RideRepository::class.java)
        val redis=mock(StringRedisTemplate::class.java, RETURNS_DEEP_STUBS)
        val nearby=mock(NearbyDriverQueryPort::class.java)
        val selection=mock(DriverSelectionService::class.java)
        val publisher=mock(ApplicationEventPublisher::class.java)
        val notifications=mock(NotificationPort::class.java)
        val offers=mock(DispatchOfferRepository::class.java)
        val current=ride()
        `when`(rides.findById(42)).thenReturn(Optional.of(current))
        `when`(redis.opsForValue().setIfAbsent(anyString(), anyString(), any(java.time.Duration::class.java))).thenReturn(true)
        `when`(redis.opsForHash<String,String>().get(RedisKeys.rideState("42"),"status")).thenReturn("SEARCHING")
        `when`(redis.opsForHash<String,String>().get(RedisKeys.rideState("42"),"nextRoundIndex")).thenReturn("0","1","2","3")
        val service=RideDispatchService(nearby,selection,MatchingProperties(),redis,publisher,
            mock(RideOfferBuilder::class.java),notifications,mock(OfferReleaseService::class.java),rides,
            mock(RideDispatchOutboxRepository::class.java),offers)
        repeat(4) { assertEquals(java.time.Duration.ofSeconds(3), service.dispatchRide(42)) }
        verify(nearby).findNearbyDrivers(GeoPoint(21.03,105.84),2.0,500L)
        verify(nearby).findNearbyDrivers(GeoPoint(21.03,105.84),3.0,500L)
        verify(nearby,times(2)).findNearbyDrivers(GeoPoint(21.03,105.84),5.0,500L)
        verifyNoInteractions(publisher, notifications)
        current.searchExpiresAt=Instant.now().minusSeconds(1)
        assertNull(service.dispatchRide(42))
        assertEquals(42L, (mockingDetails(publisher).invocations.single().arguments[0] as com.nhom15.datxebackend.ride.event.RideNoDriverFound).rideId)
    }

    @Test fun `pending offer uses the durable ledger expiry and returns the live offer id`() {
        val rides=mock(RideRepository::class.java)
        val redis=mock(StringRedisTemplate::class.java, RETURNS_DEEP_STUBS)
        val offers=mock(DispatchOfferRepository::class.java)
        val builder=mock(RideOfferBuilder::class.java)
        val current=ride()
        val expires=Instant.now().plusSeconds(12)
        `when`(redis.opsForValue().get(RedisKeys.driverLock("7"))).thenReturn("42")
        `when`(redis.opsForHash<String,String>().entries(RedisKeys.rideState("42"))).thenReturn(
            mapOf("status" to "SEARCHING", "currentDriver" to "7", "currentOfferId" to "offer-1"))
        `when`(offers.outstanding(42)).thenReturn(DispatchOffer("offer-1", 42, 7, expires))
        `when`(rides.findById(42)).thenReturn(Optional.of(current))
        val payload=mock(RideOfferPayload::class.java)
        `when`(builder.build(42, 7, expires.toEpochMilli(), "offer-1")).thenReturn(payload)
        val service=RideAcceptanceService(redis, mock(RideAssignmentPort::class.java), rides,
            mock(RideDispatchService::class.java), mock(OfferReleaseService::class.java), builder,
            MatchingProperties(), offers)
        assertSame(payload, service.pendingOffer(7))
    }

    @Test fun `an offer still renders when pickup GPS is unavailable`() {
        val rides = mock(RideRepository::class.java)
        val redis = mock(StringRedisTemplate::class.java, RETURNS_DEEP_STUBS)
        val customers = mock(com.nhom15.datxebackend.customer.repository.CustomerRepository::class.java)
        `when`(rides.findById(42)).thenReturn(Optional.of(ride()))
        `when`(redis.opsForHash<String, String>().entries(RedisKeys.driverLocation("7"))).thenReturn(emptyMap())
        `when`(redis.opsForGeo().position(RedisKeys.GEO_KEY, "7")).thenReturn(listOf(null))
        val offer = RideOfferBuilder(rides, redis, customers, MatchingProperties())
            .build(42, 7, Instant.now().plusSeconds(25).toEpochMilli(), "offer-1")
        assertEquals("offer-1", offer.offerId)
        assertNull(offer.distanceToPickupKm)
        assertNull(offer.etaToPickupMin)
        assertEquals("Đón", offer.pickup.address)
        assertTrue(offer.driverEarning > 0)
    }

    @Test fun `outstanding offer wakes the tick at expiry plus grace`() {
        val rides=mock(RideRepository::class.java)
        val redis=mock(StringRedisTemplate::class.java, RETURNS_DEEP_STUBS)
        val nearby=mock(NearbyDriverQueryPort::class.java)
        val offers=mock(DispatchOfferRepository::class.java)
        val current=ride()
        `when`(rides.findById(42)).thenReturn(Optional.of(current))
        `when`(redis.opsForValue().setIfAbsent(anyString(), anyString(), any(java.time.Duration::class.java))).thenReturn(true)
        `when`(redis.opsForHash<String,String>().get(RedisKeys.rideState("42"),"status")).thenReturn("SEARCHING")
        `when`(offers.outstanding(42)).thenReturn(DispatchOffer("offer-1",42,7,Instant.now().plusSeconds(10)))
        val service=RideDispatchService(nearby,mock(DriverSelectionService::class.java),MatchingProperties(),redis,
            mock(ApplicationEventPublisher::class.java),mock(RideOfferBuilder::class.java),mock(NotificationPort::class.java),
            mock(OfferReleaseService::class.java),rides,mock(RideDispatchOutboxRepository::class.java),offers)
        val delay=service.dispatchRide(42)
        assertNotNull(delay)
        assertTrue(delay!! >= java.time.Duration.ofSeconds(10) && delay <= java.time.Duration.ofSeconds(12))
    }

    @Test fun `conditional status race loser produces no side effects`() {
        val rides=mock(RideRepository::class.java)
        val history=mock(RideStatusHistoryRepository::class.java)
        val events=mock(EventPublisher::class.java)
        val outbox=mock(RideDispatchOutboxRepository::class.java)
        val current=ride().apply { searchExpiresAt=Instant.now().minusSeconds(1) }
        `when`(rides.findById(42)).thenReturn(Optional.of(current))
        val service=machine(rides,history,events,outbox)
        assertThrows(ConflictException::class.java) { service.transition(RideTransitionCommand(42,RideTransitionEvent.NO_DRIVER_FOUND,SYSTEM_RIDE_ACTOR)) }
        assertEquals(RideStatus.SEARCHING,current.status)
        verifyNoInteractions(history,events,outbox)
    }

    @Test fun `driver cancellation redispatches twice then ends without a third search`() {
        val rides=mock(RideRepository::class.java)
        val history=mock(RideStatusHistoryRepository::class.java)
        val events=mock(EventPublisher::class.java)
        val outbox=mock(RideDispatchOutboxRepository::class.java)
        val current=ride(RideStatus.DRIVER_ASSIGNED).apply { driverId=7 }
        `when`(rides.findById(42)).thenReturn(Optional.of(current))
        `when`(rides.compareAndSetStatus(eq(42L),anyString(),anyString(),anyLong())).thenReturn(1)
        val service=machine(rides,history,events,outbox)
        for (i in 0..2) {
            val driverId=7L+i
            current.status=RideStatus.DRIVER_ASSIGNED;current.driverId=driverId
            service.transition(RideTransitionCommand(42,RideTransitionEvent.CANCEL,driverRideActor(driverId),cancelReason="Không thể đón"))
            if(i<2) {
                assertEquals(RideStatus.SEARCHING,current.status);assertNull(current.driverId)
                assertEquals("Không thể đón",current.lastRedispatchReason)
                assertEquals(driverId,current.lastRedispatchDriverId)
                assertNotNull(current.lastRedispatchAt)
            }
            else assertEquals(RideStatus.NO_DRIVER_FOUND,current.status)
        }
        assertEquals(2,current.redispatchCount)
        verify(outbox,times(2)).schedule(42)
    }

    @Test fun `old search timeout and old generation cannot end a new redispatch`() {
        val rides = mock(RideRepository::class.java)
        val current = ride().apply { redispatchCount = 1 }
        `when`(rides.findById(42)).thenReturn(Optional.of(current))
        val service = machine(rides, mock(RideStatusHistoryRepository::class.java), mock(EventPublisher::class.java), mock(RideDispatchOutboxRepository::class.java))
        assertThrows(ConflictException::class.java) { service.transition(RideTransitionCommand(42, RideTransitionEvent.NO_DRIVER_FOUND, SYSTEM_RIDE_ACTOR)) }
        assertThrows(ConflictException::class.java) { service.transition(RideTransitionCommand(42, RideTransitionEvent.SYSTEM_FAILURE, SYSTEM_RIDE_ACTOR, expectedSearchGeneration = 0)) }
        assertEquals(RideStatus.SEARCHING, current.status)
    }

    private fun machine(rides: RideRepository,history: RideStatusHistoryRepository,events: EventPublisher,outbox: RideDispatchOutboxRepository) =
        RideStateMachine(rides,history,events,mock(VehicleHandoverReportRepository::class.java),
            mock(com.nhom15.datxebackend.chat.service.ConversationService::class.java),MatchingProperties(),outbox,
            mock(DispatchOfferRepository::class.java),mock(DriverRepository::class.java))
}
