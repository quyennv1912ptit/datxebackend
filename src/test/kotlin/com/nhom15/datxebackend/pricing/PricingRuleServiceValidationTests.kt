package com.nhom15.datxebackend.pricing

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.pricing.dto.CreatePricingRuleRequest
import com.nhom15.datxebackend.pricing.dto.UpdatePricingRuleRequest
import com.nhom15.datxebackend.pricing.repository.PricingRuleRepository
import com.nhom15.datxebackend.pricing.service.PricingRuleService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer

/** DTO annotations only guard HTTP calls; the service must reject unsupported waiting fares too. */
class PricingRuleServiceValidationTests {
    private val calls = mutableListOf<String>()
    private val repository: PricingRuleRepository = Mockito.mock(
        PricingRuleRepository::class.java,
        Answer { invocation: InvocationOnMock ->
            calls += invocation.method.name
            null
        },
    )
    private val service = PricingRuleService(repository)
    private val admin = AuthUser(1, "admin-uid", Role.ADMIN, "Admin", null, null, "ACTIVE")

    @Test
    fun `create rejects a non-zero waiting rate before touching the repository`() {
        calls.clear()
        val request = CreatePricingRuleRequest(
            baseFare = 20_000,
            perKm = 10_000,
            perMinuteWaiting = 500,
            minFare = 30_000,
            vehicleType = VehicleType.CAR,
        )

        assertThrows(BadRequestException::class.java) { service.createRule(admin, request) }

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `update rejects a non-zero waiting rate before touching the repository`() {
        calls.clear()
        val request = UpdatePricingRuleRequest(
            baseFare = 20_000,
            perKm = 10_000,
            perMinuteWaiting = 500,
            nightSurchargePercent = 0,
            returnFeeForDriver = 0,
            minFare = 30_000,
        )

        assertThrows(BadRequestException::class.java) { service.updateRule(admin, 3L, request) }

        assertEquals(emptyList<String>(), calls)
    }
}
