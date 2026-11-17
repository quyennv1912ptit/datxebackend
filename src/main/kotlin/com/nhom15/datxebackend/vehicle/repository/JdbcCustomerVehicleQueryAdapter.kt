package com.nhom15.datxebackend.vehicle.repository

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.port.CustomerVehicleQueryPort
import com.nhom15.datxebackend.common.port.CustomerVehicleSnapshot
import com.nhom15.datxebackend.common.port.VehicleBookingDetails
import com.nhom15.datxebackend.vehicle.catalog.EngineType
import com.nhom15.datxebackend.vehicle.catalog.VehicleCatalog
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/** Reads the customer's vehicle without exposing another customer's vehicle to Ride. */
@Repository
class JdbcCustomerVehicleQueryAdapter(
    private val jdbcTemplate: JdbcTemplate,
) : CustomerVehicleQueryPort {
    override fun findOwnedVehicle(vehicleId: Long, customerId: Long): CustomerVehicleSnapshot? {
        if (vehicleId <= 0 || customerId <= 0) return null

        return jdbcTemplate.query(
            """
            SELECT vehicle.*
            FROM vehicles vehicle
            JOIN customers customer ON customer.id = vehicle.customer_id
            WHERE vehicle.id = ? AND vehicle.customer_id = ? AND vehicle.archived_at IS NULL
            """.trimIndent(),
            { row, _ ->
                val transmissionType = row.getString("transmission_type")
                val transmission = Transmission.entries.find { it.name == transmissionType }
                    ?: throw BadRequestException(message = "Xe phải có transmission_type là AUTO hoặc MANUAL")
                val columns = (1..row.metaData.columnCount).map {
                    row.metaData.getColumnLabel(it).lowercase()
                }.toSet()
                val vehicleType = if ("vehicle_type" in columns) {
                    VehicleType.entries.find { it.name == row.getString("vehicle_type") }
                        ?: throw BadRequestException(message = "Loại xe không hợp lệ")
                } else VehicleType.CAR
                val specs = readSpecifications(row)
                val cc = row.getObject("engine_cc", Int::class.javaObjectType)
                val (groupId, status) = bookingRequirement(vehicleType, transmission, cc, specs)
                if (status == "OUTSIDE_MVP") {
                    throw BadRequestException(message = "Thông số khai báo của xe nằm ngoài phạm vi chuyến được hỗ trợ")
                }
                CustomerVehicleSnapshot(
                    id = row.getLong("id"),
                    ownerId = row.getLong("customer_id"),
                    transmission = transmission,
                    vehicleType = vehicleType,
                    licensePlate = row.getString("plate_number"),
                    bookingDetails = VehicleBookingDetails(
                        brand = row.getString("brand"), model = row.getString("model"),
                        color = row.getString("color"), engineCc = cc,
                        engineType = specs?.engineType?.name, electricMotorKw = specs?.electricMotorKw,
                        passengerSeats = specs?.passengerSeats, designedMassKg = specs?.designedMassKg,
                        trailerMassKg = specs?.trailerMassKg, source = "CUSTOMER_DECLARED",
                        capabilityGroupId = groupId, capabilityStatus = status, catalogVersion = VehicleCatalog.VERSION,
                    ),
                )
            },
            vehicleId,
            customerId,
        ).firstOrNull()
    }
}
