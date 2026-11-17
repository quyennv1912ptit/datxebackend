package com.nhom15.datxebackend.vehicle.repository

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.vehicle.catalog.EngineType
import com.nhom15.datxebackend.vehicle.catalog.VehicleCatalog
import com.nhom15.datxebackend.vehicle.dto.CustomerVehicleSpecifications

internal fun bookingRequirement(
    type: VehicleType, transmission: Transmission, cc: Int?, specs: CustomerVehicleSpecifications?,
): Pair<String?, String> {
    val unsupported = null to "OUTSIDE_MVP"
    val missing = null to "NEEDS_DETAILS"
    val id = when (type) {
        VehicleType.CAR -> {
            if ((specs?.passengerSeats ?: 0) > 8 || (specs?.trailerMassKg ?: 0) > 0) return unsupported
            if (specs?.passengerSeats == null || specs.trailerMassKg == null) return missing
            if (transmission == Transmission.AUTO) "car-auto" else "car-manual"
        }
        VehicleType.MOTORCYCLE -> if (specs?.engineType == EngineType.ELECTRIC) {
            val kw = specs.electricMotorKw ?: return missing
            when {
                kw < 4 -> return unsupported
                kw <= 11 -> "moto-electric-4-11"
                kw < 14 -> "moto-electric-over-11-under-14"
                else -> "moto-electric-14-plus"
            }
        } else {
            if (specs?.engineType == EngineType.HYBRID || cc == null) return missing
            when {
                cc < 50 -> return unsupported
                cc <= 125 -> "moto-50-125"
                cc < 175 -> "moto-126-174"
                else -> "moto-175-plus"
            }
        }
    }
    if (VehicleCatalog.groups.none { it.id == id && it.active && it.supported }) return unsupported
    return id to "CUSTOMER_DECLARED"
}
