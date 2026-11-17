package com.nhom15.datxebackend.admin.repository

import com.nhom15.datxebackend.admin.dto.AdminSearchFilter
import com.nhom15.datxebackend.driver.dto.AdminDriverFilter
import com.nhom15.datxebackend.driver.dto.AdminDriverSummary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class AdminDriverQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${app.verification-sync.enabled:false}") private val syncEnabled: Boolean = false,
) {
    private val verificationStatusSql = if (syncEnabled) {
        """CASE WHEN d.verification_status = 'SUSPENDED' THEN 'SUSPENDED' ELSE COALESCE((
               SELECT CASE p.status WHEN 'APPROVED' THEN 'APPROVED'
                   WHEN 'REJECTED' THEN 'REJECTED' WHEN 'REVOKED' THEN 'REJECTED'
                   ELSE 'PENDING' END
               FROM driver_verification_projection p CROSS JOIN verification_sync_checkpoint c
               WHERE p.owner_uid = d.firebase_uid AND c.caught_up_at > clock_timestamp() - interval '10 seconds'
           ), 'PENDING') END"""
    } else "COALESCE(d.verification_status, 'PENDING')"
    fun findPage(filter: AdminDriverFilter, status: String?): List<AdminDriverSummary> {
        val (where, args) = whereClause(filter, status)
        return jdbcTemplate.query(
            """SELECT id, full_name, phone_number, email, status, $verificationStatusSql AS verification_status,
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
        val (where, args) = whereClause(filter, status)
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM drivers d$where",
            Long::class.java,
            *args.toTypedArray(),
        ) ?: 0L
    }

    fun findById(id: Long): AdminDriverSummary? = jdbcTemplate.query(
        """SELECT id, full_name, phone_number, email, status, $verificationStatusSql AS verification_status,
                  COALESCE(is_flagged, false) AS flagged, COALESCE(total_trips, 0) AS total_trips,
                  average_rating, acceptance_rate, completion_rate, cancel_rate_30d,
                  COALESCE(credit_balance, 0) AS credit_balance, COALESCE(income_balance, 0) AS income_balance,
                  created_at
           FROM drivers d WHERE id = ?""".trimIndent(),
        { row, _ -> row.toSummary() },
        id,
    ).firstOrNull()

    private fun whereClause(filter: AdminDriverFilter, status: String?): Pair<String, List<Any>> {
        val predicates = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (status != null) {
            predicates += "$verificationStatusSql = ?"
            args += status
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
