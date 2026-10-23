package com.nhom15.datxebackend.ride.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class RideDispatchOutboxRepository(private val jdbcTemplate: JdbcTemplate) {
    @Transactional
    fun enqueue(rideId: Long) {
        jdbcTemplate.update(
            "INSERT INTO ride_dispatch_outbox (ride_id) VALUES (?) ON CONFLICT (ride_id) DO NOTHING",
            rideId,
        )
    }

    @Transactional
    fun recover(rideId: Long) {
        jdbcTemplate.update(
            """
            INSERT INTO ride_dispatch_outbox (ride_id) VALUES (?)
            ON CONFLICT (ride_id) DO UPDATE
            SET available_at = CURRENT_TIMESTAMP, locked_until = NULL, delivered_at = NULL, last_error = NULL
            WHERE ride_dispatch_outbox.delivered_at IS NOT NULL
            """.trimIndent(),
            rideId,
        )
    }

    @Transactional
    fun claimDue(limit: Int = 50): List<Long> = jdbcTemplate.query(
        """
        WITH due AS (
            SELECT id
            FROM ride_dispatch_outbox
            WHERE delivered_at IS NULL
              AND available_at <= CURRENT_TIMESTAMP
              AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
            ORDER BY available_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        )
        UPDATE ride_dispatch_outbox AS item
        SET locked_until = CURRENT_TIMESTAMP + INTERVAL '60 seconds',
            attempts = item.attempts + 1
        FROM due
        WHERE item.id = due.id
        RETURNING item.ride_id
        """.trimIndent(),
        { row, _ -> row.getLong("ride_id") },
        limit,
    )

    @Transactional
    fun markDelivered(rideId: Long) {
        jdbcTemplate.update(
            "UPDATE ride_dispatch_outbox SET delivered_at = CURRENT_TIMESTAMP, locked_until = NULL, last_error = NULL WHERE ride_id = ?",
            rideId,
        )
    }

    @Transactional
    fun retry(rideId: Long, error: String) {
        jdbcTemplate.update(
            """
            UPDATE ride_dispatch_outbox
            SET available_at = CURRENT_TIMESTAMP + make_interval(secs => LEAST(300, GREATEST(2, attempts * 2))),
                locked_until = NULL,
                last_error = ?
            WHERE ride_id = ? AND delivered_at IS NULL
            """.trimIndent(),
            error.take(4_000),
            rideId,
        )
    }
}
