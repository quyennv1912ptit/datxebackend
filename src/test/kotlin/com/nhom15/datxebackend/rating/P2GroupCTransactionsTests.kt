package com.nhom15.datxebackend.rating

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.driver.verification.DriverVerificationWorkflowService
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.rating.dto.CreateRideRatingRequest
import com.nhom15.datxebackend.rating.service.RideRatingService
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.RideEstimateService
import com.nhom15.datxebackend.voucher.service.VoucherService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.Optional

class P2GroupCTransactionsTests {

    @AfterEach
    fun clearTxSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `F-013 listAvailable, F-021 faceComparison, and F-029 estimateRide do not hold outer Transactional over nested REQUIRES_NEW or external HTTP`() {
        val listAvailable = VoucherService::class.java.methods.first { it.name == "listAvailable" }
        assertThat(listAvailable.getAnnotation(Transactional::class.java))
            .describedAs("F-013: listAvailable must not hold outer readOnly tx across grantWelcome REQUIRES_NEW")
            .isNull()

        val faceComparison = DriverVerificationWorkflowService::class.java.methods.first { it.name == "faceComparison" }
        assertThat(faceComparison.getAnnotation(Transactional::class.java))
            .describedAs("F-021: faceComparison must not hold @Transactional across Modal HTTP I/O")
            .isNull()

        val estimateRide = RideEstimateService::class.java.methods.first { it.name == "estimateRide" }
        assertThat(estimateRide.getAnnotation(Transactional::class.java))
            .describedAs("F-029: estimateRide must not hold @Transactional across Goong HTTP I/O")
            .isNull()
    }

    @Test
    fun `F-047 rate defers notification until AFTER_COMMIT (0 pushes on rollback) and F-048 locks ratee row FOR UPDATE`() {
        val rideRepo = Mockito.mock(RideRepository::class.java)
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        val sentNotifications = mutableListOf<com.nhom15.datxebackend.notification.api.Recipient>()
        val notifications = object : NotificationPort {
            override fun send(
                to: com.nhom15.datxebackend.notification.api.Recipient,
                payload: com.nhom15.datxebackend.notification.dto.NotificationPayload,
            ): com.nhom15.datxebackend.notification.api.DeliveryResult {
                sentNotifications.add(to)
                return com.nhom15.datxebackend.notification.api.DeliveryResult.SENT_WS
            }
        }

        val ride = Mockito.mock(Ride::class.java)
        Mockito.`when`(ride.id).thenReturn(99L)
        Mockito.`when`(ride.customerId).thenReturn(1L)
        Mockito.`when`(ride.driverId).thenReturn(10L)
        Mockito.`when`(ride.status).thenReturn(RideStatus.COMPLETED)
        Mockito.`when`(ride.completedAt).thenReturn(Instant.now())
        Mockito.`when`(rideRepo.findById(99L)).thenReturn(Optional.of(ride))

        Mockito.doReturn(0).`when`(jdbc).queryForObject(
            Mockito.contains("COUNT(*)"),
            Mockito.eq(Int::class.java),
            Mockito.eq(99L),
            Mockito.eq("DRIVER_TO_CUSTOMER"),
        )
        val lockSqlCaptor = ArgumentCaptor.forClass(String::class.java)
        Mockito.doReturn(listOf(1L)).`when`(jdbc).queryForList(lockSqlCaptor.capture(), Mockito.eq(Long::class.java), Mockito.eq(1L))
        Mockito.doReturn(1).`when`(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any())
        Mockito.doReturn(1).`when`(jdbc).update(anyString(), any(), any(), any())

        TransactionSynchronizationManager.initSynchronization()
        val service = RideRatingService(rideRepo, jdbc, notifications)
        val driver = AuthUser(10L, "d10", Role.DRIVER, "Driver 10", null, null, "ONLINE")
        service.rate(driver, 99L, CreateRideRatingRequest(stars = 5, comment = "Great"))

        // F-048: Must lock ratee row FOR UPDATE before computing AVG
        assertThat(lockSqlCaptor.allValues.any { it.contains("FOR UPDATE") }).isTrue()

        // F-047: Notification must NOT have been sent before commit!
        assertThat(sentNotifications).isEmpty()

        // Simulate rollback -> still 0 notifications
        TransactionSynchronizationManager.getSynchronizations().forEach {
            it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
        }
        assertThat(sentNotifications).isEmpty()

        // Simulate commit -> notification fires AFTER_COMMIT
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        assertThat(sentNotifications).containsExactly(com.nhom15.datxebackend.notification.api.Recipient.Customer(1L))
    }
}
