package com.nhom15.datxebackend.ride.repository

import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.config.properties.MatchingProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

data class RideCreateIdempotencyRecord(val rideId: Long, val requestHash: String)

@Repository
class RideCreateIdempotencyRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val matching: MatchingProperties,
) {
    fun find(customerId: Long, idempotencyKey: String): RideCreateIdempotencyRecord? = jdbcTemplate.query(
        "SELECT ride_id, request_hash FROM ride_create_idempotency WHERE customer_id = ? AND idempotency_key = ? AND created_at > CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond')",
        { row, _ -> RideCreateIdempotencyRecord(row.getLong("ride_id"), row.getString("request_hash")) },
        customerId,
        idempotencyKey,
        matching.idempotencyTtl.toMillis(),
    ).firstOrNull()

    @Transactional
    fun insert(customerId: Long, idempotencyKey: String, requestHash: String, rideId: Long) {
        val changed = jdbcTemplate.update(
            """INSERT INTO ride_create_idempotency (customer_id, idempotency_key, request_hash, ride_id) VALUES (?, ?, ?, ?)
               ON CONFLICT (customer_id, idempotency_key) DO UPDATE SET request_hash=EXCLUDED.request_hash,
                   ride_id=EXCLUDED.ride_id, created_at=CURRENT_TIMESTAMP
               WHERE ride_create_idempotency.created_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond')""",
            customerId,
            idempotencyKey,
            requestHash,
            rideId,
            matching.idempotencyTtl.toMillis(),
        )
        if (changed != 1) throw ConflictException(message = "Idempotency-Key đang được xử lý")
    }
}
