package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.payment.controller.*
import com.nhom15.datxebackend.payment.dto.*
import com.nhom15.datxebackend.payment.entity.*
import com.nhom15.datxebackend.payment.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.mock.env.MockEnvironment
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

class CustomerWalletEndpointsTests {
    private val actor = AuthUser(11, "customer", Role.CUSTOMER, "Khách", null, null, "ACTIVE")

    @Test fun `customer top up always uses authenticated owner`() {
        val service = mock(CustomerMockTopUpService::class.java)
        CustomerMockTopUpController(service).topUp(actor, "retry-1", DriverTopUpBody(50_000))
        verify(service).topUp(11, 50_000, "retry-1")
    }

    @Test fun `mock capability is not advertised in production even when flag is true`() {
        val wallets = mock(WalletService::class.java)
        `when`(wallets.balances(11, WalletOwner.CUSTOMER)).thenReturn(listOf(WalletBalanceDto(WalletType.CUSTOMER, 100_000)))
        val environment = MockEnvironment().withProperty("wallet.mock-topup-enabled", "true")
        environment.setActiveProfiles("prod")
        val controller = WalletQueryController(wallets, environment)
        assertFalse(controller.overview(actor).data!!.mockTopUpEnabled)
        environment.setActiveProfiles("dev")
        assertTrue(controller.overview(actor).data!!.mockTopUpEnabled)
        assertEquals(100_000, controller.overview(actor).data!!.balances.single().balance)
    }

    @Test fun `history passes authenticated owner and selected wallet to paginated query`() {
        val wallets = mock(WalletService::class.java)
        `when`(wallets.history(11, WalletOwner.CUSTOMER, 2, WalletType.CUSTOMER))
            .thenReturn(PageImpl(emptyList(), PageRequest.of(2, 20), 0))
        val page = WalletQueryController(wallets, MockEnvironment()).history(actor, 2, WalletType.CUSTOMER).data!!
        assertEquals(2, page.number)
        assertEquals(20, page.size)
        assertTrue(page.last)
        verify(wallets).history(11, WalletOwner.CUSTOMER, 2, WalletType.CUSTOMER)
    }
}
