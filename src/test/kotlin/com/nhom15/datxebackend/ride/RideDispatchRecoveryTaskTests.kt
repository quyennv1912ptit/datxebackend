package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.config.properties.MatchingProperties
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility
import com.nhom15.datxebackend.matching.service.DriverReleaseService
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
import com.nhom15.datxebackend.ride.repository.DispatchOfferRepository
import com.nhom15.datxebackend.ride.repository.RideDispatchOutboxRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.RideDispatchRecoveryTask
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.ride.service.state.RideTransitionCommand
import com.nhom15.datxebackend.ride.service.state.RideTransitionEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import org.springframework.data.domain.Page
import org.springframework.data.redis.core.Cursor
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import java.util.Optional

class RideDispatchRecoveryTaskTests {
    @Suppress("UNCHECKED_CAST")
    private val emptyCursor = mock(Cursor::class.java) as Cursor<String>

    // Kotlin inserts a null check on non-null repository parameters, so Mockito matchers
    // cannot be used here. Default answers feed the idle columns and the stale-ride scan.
    private var pendingCandidates: List<Ride> = emptyList()

    private val rides = mock(RideRepository::class.java, Answer { invocation ->
        when (invocation.method.name) {
            "findAllByStatusIn" -> Page.empty<Ride>()
            "findByStatusAndScheduledAtIsNullAndUpdatedAtBefore" -> pendingCandidates
            else -> Answers.RETURNS_DEFAULTS.answer(invocation)
        }
    })
    private val state = mock(RideStateMachine::class.java)
    private val outbox = mock(RideDispatchOutboxRepository::class.java)
    private val redis = mock(StringRedisTemplate::class.java, Answer { invocation ->
        if (invocation.method.name == "scan") emptyCursor
        else Answers.RETURNS_DEFAULTS.answer(invocation)
    })
    private val offers = mock(DispatchOfferRepository::class.java)
    private val drivers = mock(DriverRepository::class.java)
    private val release = mock(DriverReleaseService::class.java)
    private val verification = mock(DriverVerificationEligibility::class.java)
    private val transactions = mock(PlatformTransactionManager::class.java)

    private val task = RideDispatchRecoveryTask(
        rideRepository = rides,
        stateMachine = state,
        dispatchOutbox = outbox,
        redisTemplate = redis,
        properties = MatchingProperties(),
        offers = offers,
        drivers = drivers,
        driverRelease = release,
        transactionManager = transactions,
        verification = verification,
        userCache = mock(AuthenticatedUserCache::class.java),
    )

    init {
        `when`(transactions.getTransaction(any())).thenAnswer { SimpleTransactionStatus() }
        `when`(rides.findByStatus(RideStatus.SEARCHING)).thenReturn(emptyList())
        `when`(drivers.findByStatus(DriverStatus.BUSY)).thenReturn(emptyList())
    }

    @Test
    fun `stale pending ride with another active ride is expired instead of restarting search`() {
        val stale = ride()
        pendingCandidates = listOf(stale)
        `when`(rides.findByIdForUpdate(77)).thenReturn(Optional.of(stale))
        `when`(rides.existsByCustomerIdAndStatusIn(1, ACTIVE_RIDE_STATUSES)).thenReturn(true)

        task.recoverLostDispatches()

        val command = command()
        assertEquals(77L, command.rideId)
        assertEquals(RideTransitionEvent.EXPIRE, command.event)
        verify(rides).existsByCustomerIdAndStatusIn(1, ACTIVE_RIDE_STATUSES)
    }

    @Test
    fun `stale pending ride without another active ride still starts search`() {
        val stale = ride()
        pendingCandidates = listOf(stale)
        `when`(rides.findByIdForUpdate(77)).thenReturn(Optional.of(stale))
        `when`(rides.existsByCustomerIdAndStatusIn(1, ACTIVE_RIDE_STATUSES)).thenReturn(false)

        task.recoverLostDispatches()

        assertEquals(RideTransitionEvent.START_SEARCH, command().event)
        verify(rides).existsByCustomerIdAndStatusIn(1, ACTIVE_RIDE_STATUSES)
    }

    private fun command(): RideTransitionCommand {
        val invocation = mockingDetails(state).invocations.single()
        return invocation.arguments.single() as RideTransitionCommand
    }

    private fun ride() = Ride(
        customerId = 1,
        vehicleId = 2,
        type = RideType.INSTANT,
        status = RideStatus.PENDING,
        pickup = Location(GeoPoint(21.03, 105.84), "Đón"),
        dropoff = Location(GeoPoint(21.04, 105.85), "Đến"),
        vehicleType = VehicleType.CAR,
        transmission = Transmission.AUTO,
        fareEstimate = 30_000,
        paymentMethod = PaymentMethod.CASH,
    ).apply { id = 77 }
}
