package com.nhom15.datxebackend.ride.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

data class RideCreateIdempotencyRecord(val rideId: Long, val requestHash: String)

@Repository
class RideCreateIdempotencyRepository(private val jdbcTemplate: JdbcTemplate) {
    fun find(customerId: Long, idempotencyKey: String): RideCreateIdempotencyRecord? = jdbcTemplate.query(
        "SELECT ride_id, request_hash FROM ride_create_idempotency WHERE customer_id = ? AND idempotency_key = ?",
        { row, _ -> RideCreateIdempotencyRecord(row.getLong("ride_id"), row.getString("request_hash")) },
        customerId,
        idempotencyKey,
    ).firstOrNull()

    @Transactional
    fun insert(customerId: Long, idempotencyKey: String, requestHash: String, rideId: Long) {
        jdbcTemplate.update(
            "INSERT INTO ride_create_idempotency (customer_id, idempotency_key, request_hash, ride_id) VALUES (?, ?, ?, ?)",
            customerId,
            idempotencyKey,
            requestHash,
            rideId,
        )
    }
}
