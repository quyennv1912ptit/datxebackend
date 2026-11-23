package com.nhom15.datxebackend.ride.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
data class RideFareSnapshot(
    @Column(name = "pricing_rule_id", updatable = false)
    val pricingRuleId: Long,
    @Column(name = "pricing_base_fare", updatable = false)
    val baseFare: Long,
    @Column(name = "pricing_distance_fare", updatable = false)
    val distanceFare: Long,
    @Column(name = "pricing_waiting_fare", updatable = false)
    val waitingFare: Long,
    @Column(name = "pricing_night_surcharge", updatable = false)
    val nightSurcharge: Long,
    @Column(name = "pricing_return_fee", updatable = false)
    val returnFee: Long,
)
