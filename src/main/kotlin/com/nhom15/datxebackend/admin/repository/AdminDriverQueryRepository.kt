package com.nhom15.datxebackend.admin.repository

import com.nhom15.datxebackend.driver.dto.AdminDriverFilter
import com.nhom15.datxebackend.driver.dto.AdminDriverSummary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class AdminDriverQueryRepository(private val jdbcTemplate: JdbcTemplate) {
    fun findPage(filter: AdminDriverFilter, status: String?): List<AdminDriverSummary> {
        val (where, args) = whereClause(filter, status)
        return jdbcTemplate.query(
            """SELECT id, full_name, phone_number, email, status, COALESCE(verification_status, 'PENDING') AS verification_status,
                      COALESCE(is_flagged, false) AS flagged, COALESCE(total_trips, 0) AS total_trips,
                      average_rating, acceptance_rate, completion_rate, cancel_rate_30d,
                      COALESCE(credit_balance, 0) AS credit_balance, COALESCE(income_balance, 0) AS income_balance,
                      created_at
               FROM drivers$where
               ORDER BY created_at DESC, id DESC
               LIMIT ? OFFSET ?""".trimIndent(),
            { row, _ -> row.toSummary() },
            *(args + listOf(filter.size, filter.page.toLong() * filter.size)).toTypedArray(),
        )
    }

    fun count(filter: AdminDriverFilter, status: String?): Long {
        val (where, args) = whereClause(filter, status)
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM drivers$where",
            Long::class.java,
            *args.toTypedArray(),
        ) ?: 0L
    }

    fun findById(id: Long): AdminDriverSummary? = jdbcTemplate.query(
        """SELECT id, full_name, phone_number, email, status, COALESCE(verification_status, 'PENDING') AS verification_status,
                  COALESCE(is_flagged, false) AS flagged, COALESCE(total_trips, 0) AS total_trips,
                  average_rating, acceptance_rate, completion_rate, cancel_rate_30d,
                  COALESCE(credit_balance, 0) AS credit_balance, COALESCE(income_balance, 0) AS income_balance,
                  created_at
           FROM drivers WHERE id = ?""".trimIndent(),
        { row, _ -> row.toSummary() },
        id,
    ).firstOrNull()

    private fun whereClause(filter: AdminDriverFilter, status: String?): Pair<String, List<Any>> {
        val predicates = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (status != null) {
            predicates += "COALESCE(verification_status, 'PENDING') = ?"
            args += status
        }
        filter.online?.let {
            predicates += if (it) "status <> 'OFFLINE'" else "status = 'OFFLINE'"
        }
        filter.flagged?.let {
            predicates += "COALESCE(is_flagged, false) = ?"
            args += it
        }
        filter.search?.trim()?.takeIf(String::isNotEmpty)?.let {
            predicates += "(full_name ILIKE ? OR phone_number ILIKE ?)"
            val pattern = "%$it%"
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
