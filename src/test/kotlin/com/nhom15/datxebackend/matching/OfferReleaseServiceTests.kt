package com.nhom15.datxebackend.matching

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.matching.service.DispatchScripts
import com.nhom15.datxebackend.matching.service.OfferReleaseService
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.DispatchOffer
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.anyList
import org.mockito.Mockito.anyString
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.Instant
import java.util.Optional

class OfferReleaseServiceTests {
    // Một TransactionManager thật sự trả về TransactionStatus không-null cho callback
    // của TransactionTemplate; mock trả null khiến lambda Kotlin nổ NPE ở expire().
    private val transactionManager = object : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) = Unit
        override fun rollback(status: TransactionStatus) = Unit
    }

    private fun service(
        redis: StringRedisTemplate,
        offers: DispatchOfferRepository,
        rides: RideRepository = mock(RideRepository::class.java),
    ) = OfferReleaseService(
        redisTemplate = redis,
        properties = MatchingProperties(),
        offers = offers,
        notificationPort = mock(NotificationPort::class.java),
        rides = rides,
        transactionManager = transactionManager,
    )

    private fun stubRelease(redis: StringRedisTemplate, result: Long) {
        `when`(redis.execute(eq(DispatchScripts.RELEASE), anyList(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(result)
    }

    @Test
    fun `a lost release does not close the offer accepted in redis`() {
        val redis = mock(StringRedisTemplate::class.java)
        val offers = mock(DispatchOfferRepository::class.java)
        stubRelease(redis, 0L)

        assertFalse(service(redis, offers).release(42, 7, "rejected", "offer-1"))

        verifyNoInteractions(offers)
    }

    @Test
    fun `a won release still closes the durable offer as rejected`() {
        val redis = mock(StringRedisTemplate::class.java)
        val offers = mock(DispatchOfferRepository::class.java)
        stubRelease(redis, 1L)

        assertTrue(service(redis, offers).release(42, 7, "rejected", "offer-1"))

        verify(offers).close("offer-1", "REJECTED", 7L, 42L)
    }

    @Test
    fun `a won ignored release still closes the durable offer as ignored`() {
        val redis = mock(StringRedisTemplate::class.java)
        val offers = mock(DispatchOfferRepository::class.java)
        stubRelease(redis, 1L)

        assertTrue(service(redis, offers).release(42, 7, "ignored", "offer-1"))

        verify(offers).close("offer-1", "IGNORED", 7L, 42L)
        verify(offers, never()).close("offer-1", "REJECTED", 7L, 42L)
    }

    @Test
    fun `expire closes a stale durable offer when redis state is gone`() {
        val redis = mock(StringRedisTemplate::class.java, RETURNS_DEEP_STUBS)
        val offers = mock(DispatchOfferRepository::class.java)
        val rides = mock(RideRepository::class.java)
        val ride = Ride(
            customerId = 1, vehicleId = 2, type = RideType.INSTANT,
            pickup = Location(GeoPoint(21.03, 105.84), "Đón"),
            dropoff = Location(GeoPoint(21.04, 105.85), "Đến"),
            vehicleType = VehicleType.CAR, transmission = Transmission.AUTO,
            fareEstimate = 30000, paymentMethod = PaymentMethod.CASH,
            status = RideStatus.SEARCHING,
        ).apply { id = 42 }
        `when`(rides.findByIdForUpdate(42L)).thenReturn(Optional.of(ride))
        stubRelease(redis, 0L)
        val offer = DispatchOffer("offer-1", 42, 7, Instant.now().minusSeconds(60), 0)

        service(redis, offers, rides).expire(offer)

        // RELEASE = 0 không được để offer hết hạn ở OFFERED, nếu không outstanding()
        // có thể trả nó cho vòng dispatch tiếp theo dù đã có offer mới hơn.
        verify(offers).close("offer-1", "IGNORED", 7L, 42L)
    }
}
