package com.nhom15.datxebackend.admin.repository

import com.nhom15.datxebackend.admin.dto.AdminSearchFilter
import com.nhom15.datxebackend.driver.dto.AdminDriverFilter
import com.nhom15.datxebackend.driver.dto.AdminDriverSummary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import com.nhom15.datxebackend.driver.dto.DriverVerificationApplicationSummary
import java.sql.ResultSet
import java.util.UUID

@Repository
class AdminDriverQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${app.verification-sync.enabled:false}") private val syncEnabled: Boolean = false,
) {
    private val verificationStatusSql =
        """CASE WHEN d.verification_status = 'SUSPENDED' THEN 'SUSPENDED' ELSE COALESCE((
               SELECT CASE p.status WHEN 'APPROVED' THEN 'APPROVED'
                   WHEN 'REJECTED' THEN 'REJECTED' WHEN 'REVOKED' THEN 'REJECTED'
                   ELSE 'PENDING' END
               FROM driver_verification_projection p
               WHERE p.owner_uid = d.firebase_uid AND (p.native_workflow OR (
                   $syncEnabled AND p.synced_workflow AND EXISTS (
                       SELECT 1 FROM verification_sync_checkpoint c
                       WHERE c.singleton AND c.caught_up_at > clock_timestamp() - interval '10 seconds'
                   )
               ))
           ), 'PENDING') END"""

    private fun hasSuspensionColumns(): Boolean = runCatching {
        jdbcTemplate.queryForObject(
            """SELECT EXISTS (
                   SELECT 1 FROM information_schema.columns
                   WHERE table_schema = current_schema()
                     AND table_name = 'drivers'
                     AND column_name = 'account_status'
               )""".trimIndent(),
            Boolean::class.java,
        ) == true
    }.getOrDefault(false)

    private fun suspensionSelectSql(hasCols: Boolean): String = if (hasCols) {
        """COALESCE(account_status, 'ACTIVE') AS account_status,
           account_status_reason,
           COALESCE(withdrawal_frozen, false) AS withdrawal_frozen"""
    } else {
        """'ACTIVE'::text AS account_status,
           NULL::text AS account_status_reason,
           false AS withdrawal_frozen"""
    }

    fun findPage(filter: AdminDriverFilter, status: String?): List<AdminDriverSummary> {
        val hasCols = hasSuspensionColumns()
        val (where, args) = whereClause(filter, status, hasCols)
        return jdbcTemplate.query(
            """SELECT id, full_name, phone_number, email, status,
                      ${suspensionSelectSql(hasCols)},
                      $verificationStatusSql AS verification_status,
                      COALESCE(is_flagged, false) AS flagged, COALESCE(total_trips, 0) AS total_trips,
                      average_rating, acceptance_rate, completion_rate, cancel_rate_30d,
                      COALESCE(credit_balance, 0) AS credit_balance, COALESCE(income_balance, 0) AS income_balance,
                      created_at
               FROM drivers d$where
               ORDER BY created_at DESC, id DESC
               LIMIT ? OFFSET ?""".trimIndent(),
            { row, _ -> row.toSummary() },
            *(args + listOf(filter.size, filter.page.toLong() * filter.size)).toTypedArray(),
        )
    }

    fun count(filter: AdminDriverFilter, status: String?): Long {
        val (where, args) = whereClause(filter, status, hasSuspensionColumns())
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM drivers d$where",
            Long::class.java,
            *args.toTypedArray(),
        ) ?: 0L
    }

    fun findById(id: Long): AdminDriverSummary? {
        val hasCols = hasSuspensionColumns()
        return jdbcTemplate.query(
            """SELECT id, full_name, phone_number, email, status,
                      ${suspensionSelectSql(hasCols)},
                      $verificationStatusSql AS verification_status,
                      COALESCE(is_flagged, false) AS flagged, COALESCE(total_trips, 0) AS total_trips,
                      average_rating, acceptance_rate, completion_rate, cancel_rate_30d,
                      COALESCE(credit_balance, 0) AS credit_balance, COALESCE(income_balance, 0) AS income_balance,
                      created_at
               FROM drivers d WHERE id = ?""".trimIndent(),
            { row, _ -> row.toSummary() },
            id,
        ).firstOrNull()
    }

    fun findVerification(driverId: Long): DriverVerificationApplicationSummary? = jdbcTemplate.query(
        """SELECT a.id, a.status FROM driver_verification_applications a
           JOIN drivers d ON d.firebase_uid = a.owner_uid WHERE d.id = ?""",
        { row, _ -> DriverVerificationApplicationSummary(row.getObject("id", UUID::class.java), row.getString("status")) },
        driverId,
    ).firstOrNull()

    private fun whereClause(filter: AdminDriverFilter, status: String?, hasCols: Boolean = true): Pair<String, List<Any>> {
        val predicates = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (status != null) {
            predicates += "$verificationStatusSql = ?"
            args += status
        }
        if (hasCols) {
            filter.accountStatus?.trim()?.takeIf(String::isNotEmpty)?.uppercase()?.let {
                predicates += "COALESCE(account_status, 'ACTIVE') = ?"
                args += it
            }
            filter.withdrawalFrozen?.let {
                predicates += "COALESCE(withdrawal_frozen, false) = ?"
                args += it
            }
        }
        filter.online?.let {
            predicates += if (it) "status IN ('ONLINE', 'BUSY')" else "status = 'OFFLINE'"
        }
        filter.flagged?.let {
            predicates += "COALESCE(is_flagged, false) = ?"
            args += it
        }
        filter.search?.trim()?.takeIf(String::isNotEmpty)?.let {
            predicates += "(full_name ILIKE ? ESCAPE '!' OR phone_number ILIKE ? ESCAPE '!')"
            val pattern = AdminSearchFilter(q = it).pattern()
            args += pattern
            args += pattern
        }
        return if (predicates.isEmpty()) "" to args else " WHERE ${predicates.joinToString(" AND ")}" to args
    }

    private fun ResultSet.toSummary() = AdminDriverSummary(
        id = getLong("id"),
        fullName = getString("full_name"),
        phoneNumber = getString("phone_number"),
        email = getString("email"),
        status = getString("status"),
        accountStatus = getString("account_status") ?: "ACTIVE",
        accountStatusReason = getString("account_status_reason"),
        withdrawalFrozen = getBoolean("withdrawal_frozen"),
        verificationStatus = getString("verification_status"),
        flagged = getBoolean("flagged"),
        totalTrips = getInt("total_trips"),
        averageRating = getObject("average_rating", Double::class.javaObjectType),
        acceptanceRate = getObject("acceptance_rate", Double::class.javaObjectType),
        completionRate = getObject("completion_rate", Double::class.javaObjectType),
        cancelRate30d = getObject("cancel_rate_30d", Double::class.javaObjectType),
        creditBalance = getLong("credit_balance"),
        incomeBalance = getLong("income_balance"),
        createdAt = getTimestamp("created_at").toInstant(),
    )
}
