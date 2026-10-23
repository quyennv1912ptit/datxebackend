package com.nhom15.datxebackend.common.port

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType

interface CustomerVehicleQueryPort {
    fun findOwnedVehicle(vehicleId: Long, customerId: Long): CustomerVehicleSnapshot?
}

data class CustomerVehicleSnapshot(
    val id: Long,
    val ownerId: Long,
    val transmission: Transmission,
    val vehicleType: VehicleType,
    val licensePlate: String?,
)
