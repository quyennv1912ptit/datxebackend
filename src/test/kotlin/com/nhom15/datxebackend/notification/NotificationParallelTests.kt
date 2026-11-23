package com.nhom15.datxebackend.notification

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.websocket.*
import com.nhom15.datxebackend.notification.api.*
import com.nhom15.datxebackend.notification.channel.*
import com.nhom15.datxebackend.notification.dto.RideUpdatedPayload
import com.nhom15.datxebackend.notification.service.NotificationDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class NotificationParallelTests {
    @Test fun `WS and FCM start in parallel and share a bounded delivery deadline`() {
        val stomp = mock(StompChannel::class.java)
        val fcm = mock(FcmChannel::class.java)
        val to = Recipient.Customer(1)
        val payload = RideUpdatedPayload(42, RideStatus.DRIVER_ARRIVED)
        val dummy = WsEnvelope(PayloadType.RIDE_UPDATED, payload)
        val bothEntered = CountDownLatch(2)
        `when`(stomp.isAvailable(to)).thenReturn(true)
        `when`(fcm.isAvailable(to)).thenReturn(true)
        val action = org.mockito.stubbing.Answer<Boolean> {
            bothEntered.countDown()
            assertTrue(bothEntered.await(1, TimeUnit.SECONDS), "both channels must start before either finishes")
            true
        }
        doAnswer(action).`when`(stomp).send(eq(to) ?: to, any(WsEnvelope::class.java) ?: dummy)
        doAnswer(action).`when`(fcm).send(eq(to) ?: to, any(WsEnvelope::class.java) ?: dummy)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val dispatcher = NotificationDispatcher(stomp, fcm, mock(JdbcTemplate::class.java),
                tools.jackson.module.kotlin.jacksonObjectMapper(), executor, 2000)
            assertEquals(DeliveryResult.SENT_WS, dispatcher.send(to, payload))
        } finally { executor.shutdownNow() }
    }

    @Test fun `delivery timeout cancels the in-flight channel so a late push cannot outlive the result`() {
        val stomp = mock(StompChannel::class.java)
        val fcm = mock(FcmChannel::class.java)
        val to = Recipient.Customer(1)
        val payload = RideUpdatedPayload(42, RideStatus.SEARCHING)
        val dummy = WsEnvelope(PayloadType.RIDE_UPDATED, payload)
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val deliveredLate = AtomicBoolean(false)
        val action = org.mockito.stubbing.Answer<Boolean> {
            entered.countDown()
            try {
                Thread.sleep(5_000)
                deliveredLate.set(true)
            } catch (_: InterruptedException) {
                interrupted.countDown()
            }
            false
        }
        `when`(stomp.isAvailable(to)).thenReturn(false)
        `when`(fcm.isAvailable(to)).thenReturn(true)
        doAnswer(action).`when`(fcm).send(eq(to) ?: to, any(WsEnvelope::class.java) ?: dummy)

        val executor = Executors.newFixedThreadPool(2)
        try {
            val dispatcher = NotificationDispatcher(stomp, fcm, mock(JdbcTemplate::class.java),
                tools.jackson.module.kotlin.jacksonObjectMapper(), executor, 30)
            assertEquals(DeliveryResult.UNREACHABLE, dispatcher.send(to, payload))
            assertTrue(entered.await(1, TimeUnit.SECONDS), "channel must have started")
            assertTrue(interrupted.await(1, TimeUnit.SECONDS), "timed-out channel must be cancelled")
            assertFalse(deliveredLate.get(), "cancelled channel must not deliver after the timeout")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `executor overload fails gracefully so callers can retain REST fallback`() {
        val executor = Executor { throw RejectedExecutionException("full") }
        val dispatcher = NotificationDispatcher(mock(StompChannel::class.java), mock(FcmChannel::class.java),
            mock(JdbcTemplate::class.java), tools.jackson.module.kotlin.jacksonObjectMapper(), executor, 20)
        assertEquals(DeliveryResult.UNREACHABLE, dispatcher.send(Recipient.Customer(1), RideUpdatedPayload(42, RideStatus.SEARCHING)))
    }
}
