package com.nhom15.datxebackend.vehicle.repository

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.port.CustomerVehicleQueryPort
import com.nhom15.datxebackend.common.port.CustomerVehicleSnapshot
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
            WHERE vehicle.id = ? AND vehicle.customer_id = ?
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
                CustomerVehicleSnapshot(
                    id = row.getLong("id"),
                    ownerId = row.getLong("customer_id"),
                    transmission = transmission,
                    vehicleType = vehicleType,
                    licensePlate = if ("plate_number" in columns) row.getString("plate_number") else null,
                )
            },
            vehicleId,
            customerId,
        ).firstOrNull()
    }
}
