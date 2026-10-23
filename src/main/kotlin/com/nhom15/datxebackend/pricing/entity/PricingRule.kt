package com.nhom15.datxebackend.pricing.entity

import com.nhom15.datxebackend.common.entity.BaseEntity
import com.nhom15.datxebackend.common.enums.VehicleType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Enumerated
import jakarta.persistence.EnumType
import java.time.LocalTime

@Entity
@Table(name = "pricing_rules")
class PricingRule(
    @Column(name = "base_fare", nullable = false)
    var baseFare: Long,

    @Column(name = "per_km", nullable = false)
    var perKm: Long,

    @Column(name = "per_minute_waiting", nullable = false)
    var perMinuteWaiting: Long,

    @Column(name = "night_surcharge_percent", nullable = false)
    var nightSurchargePercent: Int = 0,

    @Column(name = "night_start")
    var nightStart: LocalTime? = null,

    @Column(name = "night_end")
    var nightEnd: LocalTime? = null,

    @Column(name = "return_fee_for_driver", nullable = false)
    var returnFeeForDriver: Long = 0,

    @Column(name = "min_fare", nullable = false)
    var minFare: Long,

    @Column(nullable = false)
    var active: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 30)
    var vehicleType: VehicleType,
) : BaseEntity()
