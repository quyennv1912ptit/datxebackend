package com.nhom15.datxebackend.pricing.model

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.VehicleType
import java.time.Instant

data class RideQuoteInput(
    val pickup: GeoPoint,
    val dropoff: GeoPoint,
    val pricingAt: Instant,
    val vehicleType: VehicleType,
)
