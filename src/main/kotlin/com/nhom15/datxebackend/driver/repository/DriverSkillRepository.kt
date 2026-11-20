package com.nhom15.datxebackend.driver.repository

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.driver.dto.DriverSkillResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class DriverSkillRepository(private val jdbcTemplate: JdbcTemplate) {
    fun findAllForDriver(driverId: Long): List<DriverSkillResponse> = jdbcTemplate.query(
        "SELECT id, vehicle_type, transmission, license_class, verified FROM driver_vehicle_skills WHERE driver_id = ? ORDER BY vehicle_type, transmission, license_class NULLS FIRST, id",
        { row, _ -> row.toSkill() },
        driverId,
    )

    fun verify(id: Long, verified: Boolean): Int = jdbcTemplate.update(
        "UPDATE driver_vehicle_skills SET verified = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
        verified,
        id,
    )

    private fun java.sql.ResultSet.toSkill() = DriverSkillResponse(
        id = getLong("id"),
        vehicleType = VehicleType.valueOf(getString("vehicle_type")),
        transmission = Transmission.valueOf(getString("transmission")),
        licenseClass = getString("license_class"),
        verified = getBoolean("verified"),
    )
}
