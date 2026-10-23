package com.nhom15.datxebackend.driver.repository

import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.driver.dto.DriverSkillResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class DriverSkillRepository(private val jdbcTemplate: JdbcTemplate) {
    fun findVerifiedDriverIds(
        driverIds: Collection<Long>,
        vehicleType: VehicleType,
        transmission: Transmission,
    ): Set<Long> {
        if (driverIds.isEmpty()) return emptySet()
        val placeholders = driverIds.joinToString(",") { "?" }
        val args = driverIds.map { it as Any } + listOf(vehicleType.name, transmission.name)
        return jdbcTemplate.query(
            "SELECT driver_id FROM driver_vehicle_skills WHERE driver_id IN ($placeholders) AND vehicle_type = ? AND transmission = ? AND verified = true",
            { row, _ -> row.getLong("driver_id") },
            *args.toTypedArray(),
        ).toSet()
    }

    fun findAllForDriver(driverId: Long): List<DriverSkillResponse> = jdbcTemplate.query(
        "SELECT id, vehicle_type, transmission, license_class, verified FROM driver_vehicle_skills WHERE driver_id = ? ORDER BY vehicle_type, transmission, license_class NULLS FIRST, id",
        { row, _ -> row.toSkill() },
        driverId,
    )

    fun findAllForDriverForUpdate(driverId: Long): List<DriverSkillResponse> = jdbcTemplate.query(
        "SELECT id, vehicle_type, transmission, license_class, verified FROM driver_vehicle_skills WHERE driver_id = ? ORDER BY id FOR UPDATE",
        { row, _ -> row.toSkill() },
        driverId,
    )

    fun insert(driverId: Long, vehicleType: VehicleType, transmission: Transmission, licenseClass: String?): DriverSkillResponse {
        val id = jdbcTemplate.queryForObject(
            "INSERT INTO driver_vehicle_skills (driver_id, vehicle_type, transmission, license_class, verified) VALUES (?, ?, ?, ?, false) RETURNING id",
            Long::class.java,
            driverId,
            vehicleType.name,
            transmission.name,
            licenseClass,
        ) ?: throw IllegalStateException("Không lấy được id kỹ năng vừa tạo")
        return findById(id) ?: throw IllegalStateException("Không đọc lại được kỹ năng vừa tạo")
    }

    fun delete(id: Long, driverId: Long): Int = jdbcTemplate.update(
        "DELETE FROM driver_vehicle_skills WHERE id = ? AND driver_id = ?",
        id,
        driverId,
    )

    fun findById(id: Long): DriverSkillResponse? = jdbcTemplate.query(
        "SELECT id, vehicle_type, transmission, license_class, verified FROM driver_vehicle_skills WHERE id = ?",
        { row, _ -> row.toSkill() },
        id,
    ).firstOrNull()

    fun verify(id: Long, verified: Boolean): Int = jdbcTemplate.update(
        "UPDATE driver_vehicle_skills SET verified = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
        verified,
        id,
    )

    fun hasVerifiedSkill(driverId: Long, vehicleType: VehicleType): Boolean =
        (jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM driver_vehicle_skills WHERE driver_id = ? AND vehicle_type = ? AND verified = true",
            Long::class.java,
            driverId,
            vehicleType.name,
        ) ?: 0L) > 0L

    fun hasVerifiedSkill(driverId: Long, vehicleType: VehicleType, transmission: Transmission): Boolean =
        (jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM driver_vehicle_skills WHERE driver_id = ? AND vehicle_type = ? AND transmission = ? AND verified = true",
            Long::class.java,
            driverId,
            vehicleType.name,
            transmission.name,
        ) ?: 0L) > 0L

    private fun java.sql.ResultSet.toSkill() = DriverSkillResponse(
        id = getLong("id"),
        vehicleType = VehicleType.valueOf(getString("vehicle_type")),
        transmission = Transmission.valueOf(getString("transmission")),
        licenseClass = getString("license_class"),
        verified = getBoolean("verified"),
    )
}
