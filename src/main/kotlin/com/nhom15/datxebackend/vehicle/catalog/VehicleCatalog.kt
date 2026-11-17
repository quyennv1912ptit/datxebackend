package com.nhom15.datxebackend.vehicle.catalog

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType

enum class VehicleCategory { MOTORCYCLE, PASSENGER_CAR, BUS, TRUCK }
enum class EngineType { COMBUSTION, ELECTRIC, HYBRID }

data class VehicleGroup(
    val id: String,
    val label: String,
    val category: VehicleCategory,
    val subtype: String,
    val vehicleType: VehicleType?,
    val transmission: Transmission?,
    val engineType: EngineType?,
    val displacementCc: Int? = null,
    val electricMotorKw: Double? = null,
    val passengerSeats: Int? = null,
    val designedMassKg: Int? = null,
    val trailerMassKg: Int? = 0,
    val brand: String? = null,
    val model: String? = null,
    val variant: String? = null,
    val active: Boolean = true,
    val supported: Boolean = true,
)

data class VehicleModelOption(val vehicleType: VehicleType, val brand: String, val model: String)

object VehicleCatalog {
    const val VERSION = "VN_MVP_1"

    val groups = listOf(
        motorcycle("moto-50-125", "Mô tô xăng 50–125 cm³", cc = 125),
        motorcycle("moto-126-174", "Mô tô xăng 126–174 cm³", cc = 174),
        motorcycle("moto-175-plus", "Mô tô xăng từ 175 cm³", cc = 175),
        motorcycle("moto-electric-4-11", "Mô tô điện 4–11 kW", kw = 11.0),
        motorcycle("moto-electric-over-11-under-14", "Mô tô điện trên 11 đến dưới 14 kW", kw = 13.999),
        motorcycle("moto-electric-14-plus", "Mô tô điện từ 14 kW", kw = 14.0),
        car("car-auto", "Ô tô cá nhân số tự động", Transmission.AUTO),
        car("car-manual", "Ô tô cá nhân số sàn", Transmission.MANUAL),
        VehicleGroup("bus", "Xe khách", VehicleCategory.BUS, "BUS", null, null, null,
            active = false, supported = false),
        VehicleGroup("truck", "Xe tải", VehicleCategory.TRUCK, "TRUCK", null, null, null,
            active = false, supported = false),
        VehicleGroup("car-trailer", "Ô tô kéo rơ-moóc", VehicleCategory.PASSENGER_CAR,
            "TRAILER", VehicleType.CAR, null, null, trailerMassKg = null, active = false, supported = false),
    )

    val models = listOf(
        VehicleModelOption(VehicleType.CAR, "Toyota", "Vios"),
        VehicleModelOption(VehicleType.CAR, "Mazda", "CX-5"),
        VehicleModelOption(VehicleType.MOTORCYCLE, "Honda", "Vision"),
        VehicleModelOption(VehicleType.MOTORCYCLE, "Yamaha", "Janus"),
        VehicleModelOption(VehicleType.MOTORCYCLE, "VinFast", "Evo200"),
    )

    private val activeTypes = groups.filter { it.active && it.supported }.mapNotNull { it.vehicleType }.toSet()
    val bookingTypes = mapOf(VehicleType.CAR to "Ô tô", VehicleType.MOTORCYCLE to "Xe máy")
        .filterKeys(activeTypes::contains)
    val transmissions = mapOf(Transmission.AUTO to "Số tự động", Transmission.MANUAL to "Số sàn")

    private fun motorcycle(id: String, label: String, cc: Int? = null, kw: Double? = null) =
        VehicleGroup(id, label, VehicleCategory.MOTORCYCLE, "TWO_WHEEL", VehicleType.MOTORCYCLE,
            null, if (kw == null) EngineType.COMBUSTION else EngineType.ELECTRIC,
            displacementCc = cc, electricMotorKw = kw)

    private fun car(id: String, label: String, transmission: Transmission) =
        VehicleGroup(id, label, VehicleCategory.PASSENGER_CAR, "PRIVATE_CAR", VehicleType.CAR,
            transmission, null, passengerSeats = 8)
}
