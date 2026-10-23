package com.nhom15.datxebackend.auth.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

data class AccountCleanupTask(
    val id: Long,
    val firebaseUid: String,
    val ownerRole: String,
    val ownerId: Long,
    val cloudinaryPublicIds: List<String>,
)

@Repository
class AccountCleanupOutboxRepository(private val jdbcTemplate: JdbcTemplate) {
    @Transactional
    fun enqueue(firebaseUid: String, ownerRole: String, ownerId: Long, publicIds: List<String>) {
        jdbcTemplate.execute(ConnectionCallback<Int> { connection ->
            connection.prepareStatement(
                """
                INSERT INTO account_cleanup_outbox (firebase_uid, owner_role, owner_id, cloudinary_public_ids)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (owner_role, owner_id) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, firebaseUid)
                statement.setString(2, ownerRole)
                statement.setLong(3, ownerId)
                statement.setArray(4, connection.createArrayOf("text", publicIds.filter(String::isNotBlank).distinct().toTypedArray()))
                statement.executeUpdate()
            }
        })
    }

    @Transactional
    fun claimDue(limit: Int = 20): List<AccountCleanupTask> = jdbcTemplate.query(
        """
        WITH due AS (
            SELECT id
            FROM account_cleanup_outbox
            WHERE completed_at IS NULL
              AND available_at <= CURRENT_TIMESTAMP
              AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
            ORDER BY available_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        )
        UPDATE account_cleanup_outbox AS task
        SET locked_until = CURRENT_TIMESTAMP + INTERVAL '60 seconds',
            attempts = task.attempts + 1
        FROM due
        WHERE task.id = due.id
        RETURNING task.id, task.firebase_uid, task.owner_role, task.owner_id, task.cloudinary_public_ids
        """.trimIndent(),
        { row, _ ->
            val ids = (row.getArray("cloudinary_public_ids")?.array as? Array<*>)
                ?.mapNotNull { it?.toString() }
                .orEmpty()
            AccountCleanupTask(
                id = row.getLong("id"),
                firebaseUid = row.getString("firebase_uid"),
                ownerRole = row.getString("owner_role"),
                ownerId = row.getLong("owner_id"),
                cloudinaryPublicIds = ids,
            )
        },
        limit,
    )

    @Transactional
    fun markCompleted(id: Long) {
        jdbcTemplate.update(
            "UPDATE account_cleanup_outbox SET completed_at = CURRENT_TIMESTAMP, locked_until = NULL, last_error = NULL WHERE id = ?",
            id,
        )
    }

    @Transactional
    fun retry(id: Long, error: String) {
        jdbcTemplate.update(
            """
            UPDATE account_cleanup_outbox
            SET available_at = CURRENT_TIMESTAMP + make_interval(secs => LEAST(3600, GREATEST(5, attempts * 10))),
                locked_until = NULL,
                last_error = ?
            WHERE id = ? AND completed_at IS NULL
            """.trimIndent(),
            error.take(4_000),
            id,
        )
    }
}
