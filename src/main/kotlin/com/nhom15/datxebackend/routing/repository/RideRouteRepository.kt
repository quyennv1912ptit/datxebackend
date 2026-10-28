package com.nhom15.datxebackend.routing.repository

import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.routing.model.SavedRideRoute
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.util.UUID

@Repository
class RideRouteRepository(private val jdbc: JdbcTemplate) {
    fun lock(fingerprint: String) { jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", org.springframework.jdbc.core.RowCallbackHandler { }, fingerprint) }
    private val mapper = RowMapper { rs, _ -> SavedRideRoute(rs.getString("id"), rs.getString("polyline"), rs.getDouble("distance_meters"), rs.getDouble("duration_seconds"), rs.getString("provider"), VehicleType.valueOf(rs.getString("vehicle_type")), rs.getString("limitation"), rs.getTimestamp("expires_at").toInstant()) }
    fun find(fingerprint: String): SavedRideRoute? = jdbc.query("SELECT * FROM ride_route_quotes WHERE fingerprint = ?", mapper, fingerprint).firstOrNull()
    fun save(fingerprint: String, route: SavedRideRoute) {
        jdbc.update("""INSERT INTO ride_route_quotes(id, fingerprint, polyline, distance_meters, duration_seconds, provider, vehicle_type, limitation, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(fingerprint) DO UPDATE SET id=excluded.id, polyline=excluded.polyline, distance_meters=excluded.distance_meters, duration_seconds=excluded.duration_seconds, provider=excluded.provider, vehicle_type=excluded.vehicle_type, limitation=excluded.limitation, expires_at=excluded.expires_at""",
            UUID.fromString(route.id), fingerprint, route.polyline, route.distanceMeters, route.durationSeconds, route.provider, route.vehicleType.name, route.limitation, Timestamp.from(route.expiresAt))
    }
}
