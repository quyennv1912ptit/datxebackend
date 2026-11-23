package com.nhom15.datxebackend.security

import com.nhom15.datxebackend.customer.service.CustomerPlaceService
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.support.ticket.TicketService
import com.nhom15.datxebackend.voucher.service.VoucherCampaignPublished
import com.nhom15.datxebackend.voucher.service.VoucherCampaignPublishedListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.util.Properties

class P2GroupASecurityPrivacyTests {

    @Test
    fun `F-015 application-production and application-prod disable Swagger and health details`() {
        val productionProps = Properties().apply {
            java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/application-production.properties")).use(::load)
        }
        val prodProps = Properties().apply {
            java.nio.file.Files.newInputStream(java.nio.file.Path.of("src/main/resources/application-prod.properties")).use(::load)
        }

        for (props in listOf(productionProps, prodProps)) {
            assertThat(props.getProperty("springdoc.api-docs.enabled")).isEqualTo("false")
            assertThat(props.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false")
            assertThat(props.getProperty("management.endpoint.health.show-details")).isEqualTo("never")
            assertThat(props.getProperty("management.endpoint.health.show-components")).isEqualTo("never")
            assertThat(props.getProperty("app.verification.require-protected-storage")).isEqualTo("true")
        }
    }

    @Test
    fun `F-033 VoucherCampaignPublishedListener queries active customers table instead of non-existent users table`() {
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        val deliveredTo = mutableListOf<Recipient>()
        val notifications = object : NotificationPort {
            override fun send(
                to: Recipient,
                payload: com.nhom15.datxebackend.notification.dto.NotificationPayload,
            ): com.nhom15.datxebackend.notification.api.DeliveryResult {
                deliveredTo.add(to)
                return com.nhom15.datxebackend.notification.api.DeliveryResult.SENT_WS
            }
        }
        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        Mockito.doReturn(listOf(10L, 20L)).`when`(jdbc)
            .queryForList(sqlCaptor.capture(), eq(Long::class.java))

        val listener = VoucherCampaignPublishedListener(jdbc, notifications)
        listener.onCampaignPublished(
            VoucherCampaignPublished(1L, "TET50", "Tết", "Giảm 50%", 100, "Ưu đãi Tết"),
        )

        val sql = sqlCaptor.value.replace(Regex("\\s+"), " ")
        assertThat(sql).contains("FROM customers")
        assertThat(sql).doesNotContain("FROM users")
        assertThat(sql).contains("deleted_at IS NULL")
        assertThat(deliveredTo).containsExactly(Recipient.Customer(10L), Recipient.Customer(20L))
    }

    @Test
    fun `F-035 CustomerPlaceService popular enforces k-anonymity of at least 5 distinct customers`() {
        val rides = Mockito.mock(RideRepository::class.java)
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        val tickets = Mockito.mock(TicketService::class.java)
        val sqlCaptor = ArgumentCaptor.forClass(String::class.java)
        Mockito.doReturn(emptyList<Any>()).`when`(jdbc)
            .query(sqlCaptor.capture(), any<RowMapper<Any>>())

        val service = CustomerPlaceService(rides, jdbc, tickets)
        service.popular(proximity = null, limit = 10)

        val sql = sqlCaptor.value.replace(Regex("\\s+"), " ")
        assertThat(sql).contains("HAVING COUNT(DISTINCT customer_id) >= 5")
    }
}
