package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.notification.api.*
import com.nhom15.datxebackend.notification.dto.WithdrawalNotificationPayload
import com.nhom15.datxebackend.notification.service.WithdrawalNotificationListener
import com.nhom15.datxebackend.notification.service.WithdrawalNotificationSender
import com.nhom15.datxebackend.payment.entity.WithdrawalStatus
import com.nhom15.datxebackend.payment.event.WithdrawalReviewed
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.context.annotation.*
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource
import java.sql.Connection

class WithdrawalNotificationTests {
    @Configuration
    @EnableTransactionManagement
    class Config {
        @Bean fun notifications(): NotificationPort = mock(NotificationPort::class.java)
        @Bean fun paymentSender(port: NotificationPort) = com.nhom15.datxebackend.notification.service.PaymentNotificationSender(port)
        @Bean fun paymentListener(sender: com.nhom15.datxebackend.notification.service.PaymentNotificationSender) = com.nhom15.datxebackend.notification.service.PaymentNotificationListener(sender)
        @Bean fun sender(port: NotificationPort) = WithdrawalNotificationSender(port)
        @Bean fun listener(sender: WithdrawalNotificationSender) = WithdrawalNotificationListener(sender)
        @Bean fun dataSource(): DataSource = mock(DataSource::class.java).also { source ->
            `when`(source.connection).thenAnswer { mock(Connection::class.java).also { `when`(it.autoCommit).thenReturn(true) } }
        }
        @Bean fun transactionManager(source: DataSource) = DataSourceTransactionManager(source)
    }

    @Test fun `notification is sent only after successful commit and not after rollback`() {
        AnnotationConfigApplicationContext(Config::class.java).use { context ->
            val port = context.getBean(NotificationPort::class.java)
            val tx = TransactionTemplate(context.getBean(DataSourceTransactionManager::class.java))
            val event = WithdrawalReviewed(9, 7, 500_000, WithdrawalStatus.SUCCESS, null)
            tx.executeWithoutResult {
                context.publishEvent(event)
                verifyNoInteractions(port)
            }
            verify(port).send(Recipient.Driver(7), WithdrawalNotificationPayload(9, 500_000, WithdrawalStatus.SUCCESS, null))
            clearInvocations(port)
            assertThrows(IllegalStateException::class.java) {
                tx.executeWithoutResult { context.publishEvent(event); error("rollback") }
            }
            verifyNoInteractions(port)
            doThrow(IllegalStateException("FCM unavailable")).`when`(port).send(Recipient.Driver(7),
                WithdrawalNotificationPayload(9, 500_000, WithdrawalStatus.SUCCESS, null))
            assertDoesNotThrow { tx.executeWithoutResult { context.publishEvent(event) } }
        }
    }

    @Test fun `notification carries amount and rejection reason`() {
        val payload = WithdrawalNotificationPayload(9, 500_000, WithdrawalStatus.REJECTED, "Sai số tài khoản")
        assertTrue(payload.body.contains("500.000đ"))
        assertTrue(payload.body.contains("Sai số tài khoản"))
        assertTrue(payload.body.contains("hoàn lại"))
    }

    @Test fun `payment method change reaches driver and customer only after commit`() {
        AnnotationConfigApplicationContext(Config::class.java).use { context ->
            val port = context.getBean(NotificationPort::class.java)
            val tx = TransactionTemplate(context.getBean(DataSourceTransactionManager::class.java))
            val event = com.nhom15.datxebackend.payment.event.PaymentUpdated(42,1,7,
                com.nhom15.datxebackend.common.enums.PaymentMethod.CASH,
                com.nhom15.datxebackend.common.enums.PaymentMethod.WALLET,"PAID",100_000)
            val payload = com.nhom15.datxebackend.notification.dto.PaymentNotificationPayload(42,event.fromMethod,event.method,event.status,event.amount)
            tx.executeWithoutResult { context.publishEvent(event); verifyNoInteractions(port) }
            verify(port).send(Recipient.Driver(7),payload)
            verify(port).send(Recipient.Customer(1),payload)
            assertTrue(payload.body.contains("không thu thêm tiền mặt"))
            clearInvocations(port)
            assertThrows(IllegalStateException::class.java) {
                tx.executeWithoutResult { context.publishEvent(event); error("rollback") }
            }
            verifyNoInteractions(port)
        }
    }
}
