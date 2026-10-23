package com.nhom15.datxebackend.pricing.service

import com.nhom15.datxebackend.pricing.dto.PricingRuleResponse
import com.nhom15.datxebackend.pricing.entity.PricingRule

internal object PricingRuleResponseMapper {
    fun toResponse(rule: PricingRule) = PricingRuleResponse(
        id = rule.id,
        baseFare = rule.baseFare,
        perKm = rule.perKm,
        perMinuteWaiting = rule.perMinuteWaiting,
        nightSurchargePercent = rule.nightSurchargePercent,
        nightStart = rule.nightStart,
        nightEnd = rule.nightEnd,
        returnFeeForDriver = rule.returnFeeForDriver,
        minFare = rule.minFare,
        active = rule.active,
        vehicleType = rule.vehicleType,
        createdAt = rule.createdAt,
        updatedAt = rule.updatedAt,
    )
}
