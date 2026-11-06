package com.nhom15.datxebackend.ride.repository

import java.time.Duration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

data class DispatchOffer(val offerId: String, val rideId: Long, val driverId: Long, val expiresAt: Instant, val generation: Int = 0)

@Repository
class DispatchOfferRepository(private val jdbc: JdbcTemplate) {
    fun outstanding(rideId: Long): DispatchOffer? = jdbc.query(
        "SELECT * FROM ride_dispatch_offers WHERE ride_id=? AND status='OFFERED'",
        { rs, _ -> DispatchOffer(rs.getString("offer_id"), rs.getLong("ride_id"), rs.getLong("driver_id"), rs.getTimestamp("expires_at").toInstant(), rs.getInt("generation")) }, rideId,
    ).firstOrNull()

    fun attemptedDrivers(rideId: Long): Set<String> = jdbc.query(
        "SELECT driver_id FROM ride_dispatch_offers WHERE ride_id=?", { rs, _ -> rs.getLong(1).toString() }, rideId,
    ).toSet()

    fun ignoredCounts(driverIds: List<Long>, window: Duration): Map<Long, Int> {
        if (driverIds.isEmpty()) return emptyMap()
        val placeholders = driverIds.joinToString(",") { "?" }
        return jdbc.query(
            "SELECT driver_id,count(*) AS ignored FROM ride_dispatch_offers WHERE status='IGNORED' " +
                "AND created_at > CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond') AND driver_id IN ($placeholders) GROUP BY driver_id",
            { rs, _ -> rs.getLong("driver_id") to rs.getInt("ignored") }, window.toMillis(), *driverIds.toTypedArray(),
        ).toMap()
    }

    fun record(offer: DispatchOffer): Boolean = jdbc.update(
        """INSERT INTO ride_dispatch_offers(offer_id,ride_id,driver_id,expires_at,status,generation)
           SELECT ?,id,?,?,'OFFERED',? FROM rides WHERE id=? AND status='SEARCHING'
             AND redispatch_count=? AND search_expires_at >= ? AND ? > CURRENT_TIMESTAMP
           ON CONFLICT DO NOTHING""",
        offer.offerId, offer.driverId, java.sql.Timestamp.from(offer.expiresAt), offer.generation, offer.rideId, offer.generation,
        java.sql.Timestamp.from(offer.expiresAt), java.sql.Timestamp.from(offer.expiresAt),
    ) == 1

    fun close(offerId: String, status: String, driverId: Long? = null, rideId: Long? = null): Boolean = jdbc.update(
        "UPDATE ride_dispatch_offers SET status=? WHERE offer_id=? AND status='OFFERED' AND (?::bigint IS NULL OR driver_id=?) AND (?::bigint IS NULL OR ride_id=?)", status, offerId, driverId, driverId, rideId, rideId,
    ) == 1

    fun closeRide(rideId: Long) = jdbc.update(
        "UPDATE ride_dispatch_offers SET status='CANCELLED' WHERE ride_id=? AND status='OFFERED'", rideId,
    )

    fun driverHasOffer(driverId: Long): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM ride_dispatch_offers WHERE driver_id=? AND status='OFFERED' AND expires_at>CURRENT_TIMESTAMP)",
        Boolean::class.java, driverId,
    ) == true
}
