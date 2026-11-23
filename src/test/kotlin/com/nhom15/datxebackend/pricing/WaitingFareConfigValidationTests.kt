package com.nhom15.datxebackend.pricing

import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.pricing.dto.CreatePricingRuleRequest
import com.nhom15.datxebackend.pricing.dto.UpdatePricingRuleRequest
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WaitingFareConfigValidationTests {
    private val validator: Validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `non-zero per minute waiting is rejected on both config write DTOs`() {
        val create = CreatePricingRuleRequest(
            baseFare = 20_000,
            perKm = 10_000,
            perMinuteWaiting = 500,
            minFare = 30_000,
            vehicleType = VehicleType.CAR,
        )
        val update = UpdatePricingRuleRequest(
            baseFare = 20_000,
            perKm = 10_000,
            perMinuteWaiting = 500,
            nightSurchargePercent = 0,
            returnFeeForDriver = 0,
            minFare = 30_000,
        )

        for (violations in listOf(validator.validate(create), validator.validate(update))) {
            val waitingViolation = violations.singleOrNull { it.propertyPath.toString() == "perMinuteWaiting" }
            assertTrue(waitingViolation != null, "perMinuteWaiting must be rejected when non-zero")
            assertTrue(waitingViolation!!.message.contains("perMinuteWaiting"))
        }
    }

    @Test
    fun `zero per minute waiting keeps current configs valid`() {
        val create = CreatePricingRuleRequest(
            baseFare = 20_000,
            perKm = 10_000,
            perMinuteWaiting = 0,
            minFare = 30_000,
            vehicleType = VehicleType.CAR,
        )
        assertTrue(validator.validate(create).none { it.propertyPath.toString() == "perMinuteWaiting" })
    }
}
