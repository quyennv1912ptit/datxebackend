package com.nhom15.datxebackend.ride.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

data class DispatchJob(val rideId: Long, val revision: Long, val token: String, val failures: Int, val generation: Int)

/** PostgreSQL durable delayed queue. Revision + token fence stale workers' acknowledgements. */
@Repository
class RideDispatchOutboxRepository(private val jdbcTemplate: JdbcTemplate) {
    @Transactional
    fun enqueue(rideId: Long) = schedule(rideId)

    @Transactional
    fun schedule(rideId: Long, delay: Duration = Duration.ZERO) {
        jdbcTemplate.update(
            """INSERT INTO ride_dispatch_outbox (ride_id, available_at)
               VALUES (?, CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'))
               ON CONFLICT (ride_id) DO UPDATE SET
                 available_at = LEAST(EXCLUDED.available_at, CASE WHEN ride_dispatch_outbox.delivered_at IS NULL
                     THEN ride_dispatch_outbox.available_at ELSE EXCLUDED.available_at END),
                 delivered_at = NULL, locked_until = NULL, claim_token = NULL,
                 revision = ride_dispatch_outbox.revision + 1, failures = 0, last_error = NULL""",
            rideId, delay.toMillis(),
        )
    }

    @Transactional
    fun recover(rideId: Long) {
        jdbcTemplate.update(
            """INSERT INTO ride_dispatch_outbox(ride_id) VALUES (?)
               ON CONFLICT(ride_id) DO UPDATE SET available_at=CURRENT_TIMESTAMP, delivered_at=NULL,
                   locked_until=NULL, claim_token=NULL, revision=ride_dispatch_outbox.revision+1
               WHERE ride_dispatch_outbox.delivered_at IS NOT NULL
                  OR ride_dispatch_outbox.locked_until < CURRENT_TIMESTAMP""", rideId,
        )
    }

    @Transactional
    fun claimDue(limit: Int = 50): List<DispatchJob> {
        val token = UUID.randomUUID().toString()
        return jdbcTemplate.query(
            """WITH due AS (
                 SELECT id FROM ride_dispatch_outbox WHERE delivered_at IS NULL
                   AND available_at <= CURRENT_TIMESTAMP
                   AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
                 ORDER BY available_at, id LIMIT ? FOR UPDATE SKIP LOCKED
               ) UPDATE ride_dispatch_outbox item
                 SET locked_until=CURRENT_TIMESTAMP + INTERVAL '60 seconds',
                     attempts=item.attempts+1, claim_token=?
               FROM due WHERE item.id=due.id RETURNING item.ride_id,item.revision,item.claim_token,item.failures, (SELECT redispatch_count FROM rides WHERE id=item.ride_id) AS generation""",
            { row, _ -> DispatchJob(row.getLong("ride_id"), row.getLong("revision"), row.getString("claim_token"), row.getInt("failures"), row.getInt("generation")) },
            limit, token,
        )
    }

    /** Called inside the ride transaction, after locking the ride, to fence terminal failure decisions. */
    fun lockCurrent(job: DispatchJob): Boolean = jdbcTemplate.query(
        "SELECT ride_id FROM ride_dispatch_outbox WHERE ride_id=? AND revision=? AND claim_token=? AND locked_until>CURRENT_TIMESTAMP FOR UPDATE",
        { row, _ -> row.getLong("ride_id") }, job.rideId, job.revision, job.token,
    ).isNotEmpty()

    @Transactional
    fun finish(job: DispatchJob, nextDelay: Duration? = null, error: String? = null) {
        jdbcTemplate.update(
            """UPDATE ride_dispatch_outbox SET
                 delivered_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                 available_at=CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                 failures=CASE WHEN ? THEN failures+1 ELSE 0 END, locked_until=NULL, claim_token=NULL, last_error=?
               WHERE ride_id=? AND revision=? AND claim_token=?""",
            nextDelay == null, nextDelay?.toMillis() ?: 0L, error != null, error?.take(4000), job.rideId, job.revision, job.token,
        )
    }
}
