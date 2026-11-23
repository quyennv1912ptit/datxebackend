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
    /** Locally encrypted eKYC capture files, decoded from the namespaced outbox entries. */
    val verificationStorageKeys: List<String> = emptyList(),
)

@Repository
class AccountCleanupOutboxRepository(private val jdbcTemplate: JdbcTemplate) {
    /**
     * No migration is owned here, so local storage keys share the existing text[] column.
     * The prefix cannot collide with a Cloudinary public id (those always start with a folder
     * such as `app_dat_xe/` or `datxe/`).
     */
    @Transactional
    fun enqueue(
        firebaseUid: String,
        ownerRole: String,
        ownerId: Long,
        publicIds: List<String>,
        verificationStorageKeys: List<String> = emptyList(),
    ) {
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
                val encoded = publicIds.filter(String::isNotBlank).distinct() +
                    verificationStorageKeys.filter(String::isNotBlank).distinct()
                        .map(::encodeVerificationStorageKey)
                statement.setArray(4, connection.createArrayOf("text", encoded.distinct().toTypedArray()))
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
                cloudinaryPublicIds = ids.filterNot { it.startsWith(VERIFICATION_STORAGE_PREFIX) },
                verificationStorageKeys = ids.filter { it.startsWith(VERIFICATION_STORAGE_PREFIX) }
                    .map { it.removePrefix(VERIFICATION_STORAGE_PREFIX) },
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

    companion object {
        const val VERIFICATION_STORAGE_PREFIX = "verification-storage:"

        fun encodeVerificationStorageKey(storageKey: String): String = "$VERIFICATION_STORAGE_PREFIX$storageKey"
    }
}
