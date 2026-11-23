package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.payment.controller.*
import com.nhom15.datxebackend.payment.entity.*
import com.nhom15.datxebackend.payment.service.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

/** Admin mock top-up phải đi qua receipt chống lặp, không gọi WalletService.topUp trực tiếp. */
class AdminMockTopUpIdempotencyTests {
    private data class OwnerCall(val ownerId: Long, val amount: Long, val key: String, val walletType: WalletType?)
    private data class SelfCall(val ownerId: Long, val amount: Long, val key: String)

    private val driverCalls = mutableListOf<OwnerCall>()
    private val customerCalls = mutableListOf<OwnerCall>()
    private val driverMock = mock(DriverMockTopUpService::class.java) { inv ->
        if (inv.method.name == "topUpForOwner") {
            val a = inv.arguments
            driverCalls += OwnerCall(a[0] as Long, a[1] as Long, a[2] as String, a[3] as WalletType)
        }
        null
    }
    private val customerMock = mock(CustomerMockTopUpService::class.java) { inv ->
        if (inv.method.name == "topUpForOwner") {
            val a = inv.arguments
            customerCalls += OwnerCall(a[0] as Long, a[1] as Long, a[2] as String, null)
        }
        null
    }
    private val controller = MockWalletController(driverMock, customerMock)

    @Test
    fun `customer wallet dispatch keeps the admin supplied owner and request key`() {
        driverCalls.clear()
        customerCalls.clear()
        val body = TopUpBody(userId = 11, walletType = WalletType.CUSTOMER, amount = 50_000, requestKey = "cust-1")

        controller.topUp(null, body)

        assertEquals(listOf(OwnerCall(11, 50_000, "cust-1", null)), customerCalls)
        assertEquals(emptyList<OwnerCall>(), driverCalls)
    }

    @Test
    fun `driver wallets dispatch by wallet type with the same request key`() {
        driverCalls.clear()
        customerCalls.clear()

        controller.topUp(null, TopUpBody(7, WalletType.CREDIT, 100_000, requestKey = "credit-1"))
        controller.topUp(null, TopUpBody(7, WalletType.CASH, 200_000, requestKey = "cash-1"))

        assertEquals(
            listOf(
                OwnerCall(7, 100_000, "credit-1", WalletType.CREDIT),
                OwnerCall(7, 200_000, "cash-1", WalletType.CASH),
            ),
            driverCalls,
        )
        assertEquals(emptyList<OwnerCall>(), customerCalls)
    }

    @Test
    fun `Idempotency-Key header is accepted and is required when body has no key`() {
        driverCalls.clear()
        customerCalls.clear()
        val body = TopUpBody(7, WalletType.CREDIT, 50_000, requestKey = "body-key")

        controller.topUp("header-key", body)

        assertEquals(listOf(OwnerCall(7, 50_000, "header-key", WalletType.CREDIT)), driverCalls)
        assertThrows(BadRequestException::class.java) {
            controller.topUp(" ", TopUpBody(7, WalletType.CREDIT, 50_000))
        }
        assertThrows(BadRequestException::class.java) {
            controller.topUp(null, TopUpBody(7, WalletType.CREDIT, 50_000))
        }
        assertEquals(1, driverCalls.size)
    }

    @Test
    fun `wrong user type or blank key is rejected before any wallet mutation`() {
        driverCalls.clear()
        customerCalls.clear()

        assertThrows(BadRequestException::class.java) {
            controller.topUp("k", TopUpBody(11, WalletType.CUSTOMER, 50_000, WalletOwner.DRIVER))
        }
        assertThrows(BadRequestException::class.java) {
            controller.topUp("k", TopUpBody(7, WalletType.CASH, 50_000, WalletOwner.CUSTOMER))
        }
        assertEquals(emptyList<OwnerCall>(), driverCalls)
        assertEquals(emptyList<OwnerCall>(), customerCalls)
    }

    @Test
    fun `self service driver top up still uses only the authenticated owner`() {
        val calls = mutableListOf<SelfCall>()
        val service = mock(DriverMockTopUpService::class.java) { inv ->
            if (inv.method.name == "topUp") {
                val a = inv.arguments
                calls += SelfCall(a[0] as Long, a[1] as Long, a[2] as String)
            }
            null
        }
        val actor = AuthUser(7, "driver", Role.DRIVER, "Tài", null, null, "ACTIVE")

        DriverMockTopUpController(service).topUp(actor, "self-1", DriverTopUpBody(50_000))

        assertEquals(listOf(SelfCall(7, 50_000, "self-1")), calls)
    }
}
