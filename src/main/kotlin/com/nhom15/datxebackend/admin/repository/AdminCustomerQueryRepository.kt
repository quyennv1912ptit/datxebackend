package com.nhom15.datxebackend.admin.repository

import com.nhom15.datxebackend.admin.dto.AdminSearchFilter
import com.nhom15.datxebackend.admin.dto.AdminWalletFilter
import com.nhom15.datxebackend.admin.dto.AdminCustomerFilter
import com.nhom15.datxebackend.admin.dto.AdminCustomerRidesFilter
import com.nhom15.datxebackend.admin.dto.AdminCustomerSummary
import com.nhom15.datxebackend.admin.dto.AdminWalletTransaction
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.UserStatus
import com.nhom15.datxebackend.ride.dto.response.RideSummaryResponse
import com.nhom15.datxebackend.ride.entity.RideType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Repository
class AdminCustomerQueryRepository(private val jdbcTemplate: JdbcTemplate) {
    fun count(filter: AdminCustomerFilter): Long {
        val (where, args) = customerWhere(filter)
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customers c$where", Long::class.java, *args.toTypedArray()) ?: 0L
    }

    fun findPage(filter: AdminCustomerFilter): List<AdminCustomerSummary> {
        val (where, args) = customerWhere(filter)
        val sortColumn = when (filter.sort) {
            "createdAt" -> "c.created_at"
            "totalTrips" -> "stats.total_trips"
            "cancelRate30d" -> "stats.cancel_rate_30d"
            else -> throw IllegalArgumentException("sort must be validated by service")
        }
        val order = "ORDER BY $sortColumn DESC NULLS LAST, c.id DESC"
        val sql = if (filter.sort == "createdAt") {
            // The default sort can page customers before reading their ride histories.
            """SELECT $SUMMARY_COLUMNS
               FROM (SELECT * FROM customers c$where $order LIMIT ? OFFSET ?) c
               $RIDE_STATS_JOIN
               $order"""
        } else {
            """SELECT $SUMMARY_COLUMNS
               FROM customers c
               $RIDE_STATS_JOIN$where $order LIMIT ? OFFSET ?"""
        }
        return jdbcTemplate.query(
            sql.trimIndent(),
            { row, _ -> row.toSummary() },
            *(args + listOf(filter.size, filter.page.toLong() * filter.size)).toTypedArray(),
        )
    }

    fun findById(id: Long): AdminCustomerSummary? = jdbcTemplate.query(
        """SELECT $SUMMARY_COLUMNS
           FROM customers c
           $RIDE_STATS_JOIN
           WHERE c.id = ? AND c.deleted_at IS NULL""".trimIndent(),
        { row, _ -> row.toSummary() },
        id,
    ).firstOrNull()

    fun findDetailFields(id: Long): CustomerDetailFields? = jdbcTemplate.query(
        """SELECT date_of_birth, gender, default_payment_method
           FROM customers WHERE id = ? AND deleted_at IS NULL""".trimIndent(),
        { row, _ ->
            CustomerDetailFields(
                dateOfBirth = row.getObject("date_of_birth", LocalDate::class.java),
                gender = row.getString("gender"),
                defaultPaymentMethod = row.getString("default_payment_method"),
            )
        },
        id,
    ).firstOrNull()

    fun countRides(customerId: Long, filter: AdminCustomerRidesFilter, status: RideStatus?): Long {
        val (where, args) = ridesWhere(customerId, filter, status)
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rides$where", Long::class.java, *args.toTypedArray()) ?: 0L
    }

    fun findRides(customerId: Long, filter: AdminCustomerRidesFilter, status: RideStatus?): List<RideSummaryResponse> {
        val (where, args) = ridesWhere(customerId, filter, status)
        return jdbcTemplate.query(
            """SELECT id, pickup_address, dropoff_address, type, status, fare_estimate, final_fare, scheduled_at, created_at
               FROM rides$where ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?""".trimIndent(),
            { row, _ -> row.toRideSummary() },
            *(args + listOf(filter.size, filter.page.toLong() * filter.size)).toTypedArray(),
        )
    }

    private fun walletWhere(customerId: Long, search: AdminSearchFilter, filter: AdminWalletFilter): Pair<String, List<Any>> {
        val predicates = mutableListOf("customer_id = ?")
        val args = mutableListOf<Any>(customerId)
        filter.type?.let { predicates += "type = ?"; args += it }
        filter.status?.let { predicates += "status = ?"; args += it }
        search.createdFrom?.let { predicates += "created_at >= ?"; args += Timestamp.from(it) }
        search.createdTo?.let { predicates += "created_at < ?"; args += Timestamp.from(it) }
        search.q?.trim()?.takeIf(String::isNotEmpty)?.let {
            predicates += "(LOWER(description) LIKE ? ESCAPE '!' OR id::text = ? OR ride_id::text = ?)"
            args.addAll(listOf(search.pattern(), it.removePrefix("#"), it.removePrefix("#")))
        }
        return " WHERE " + predicates.joinToString(" AND ") to args
    }

    fun countWalletTransactions(customerId: Long, search: AdminSearchFilter = AdminSearchFilter(), filter: AdminWalletFilter = AdminWalletFilter()): Long {
        val (where, args) = walletWhere(customerId, search, filter)
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_transactions$where", Long::class.java, *args.toTypedArray()) ?: 0L
    }

    fun findWalletTransactions(customerId: Long, page: Int, size: Int, search: AdminSearchFilter = AdminSearchFilter(), filter: AdminWalletFilter = AdminWalletFilter()): List<AdminWalletTransaction> {
        val (where, args) = walletWhere(customerId, search, filter)
        return jdbcTemplate.query(
        """SELECT id, ride_id, amount, type, status, description, wallet_type, balance_after, created_at
           FROM wallet_transactions$where ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?""".trimIndent(),
        { row, _ ->
            AdminWalletTransaction(
                id = row.getLong("id"),
                rideId = row.getObject("ride_id", Long::class.javaObjectType),
                amount = row.getLong("amount"),
                type = row.getString("type"),
                status = row.getString("status"),
                description = row.getString("description"),
                walletType = row.getString("wallet_type"),
                balanceAfter = row.getObject("balance_after", Long::class.javaObjectType),
                createdAt = row.getTimestamp("created_at").toInstant(),
            )
        },
        *(args + listOf(size, page.toLong() * size)).toTypedArray(),
        )
    }

    private fun customerWhere(filter: AdminCustomerFilter): Pair<String, List<Any>> {
        val predicates = mutableListOf("c.deleted_at IS NULL")
        val args = mutableListOf<Any>()
        filter.status?.let { predicates += "c.status = ?"; args += it.name }
        filter.flagged?.let { predicates += "COALESCE(c.is_flagged, false) = ?"; args += it }
        filter.q?.trim()?.takeIf(String::isNotEmpty)?.let {
            predicates += "(c.full_name ILIKE ? ESCAPE '!' OR c.phone_number ILIKE ? ESCAPE '!' OR c.email ILIKE ? ESCAPE '!')"
            val pattern = AdminSearchFilter(q = it).pattern()
            args += pattern
            args += pattern
            args += pattern
        }
        return " WHERE ${predicates.joinToString(" AND ")}" to args
    }

    private fun ridesWhere(customerId: Long, filter: AdminCustomerRidesFilter, status: RideStatus?): Pair<String, List<Any>> {
        val predicates = mutableListOf("customer_id = ?")
        val args = mutableListOf<Any>(customerId)
        status?.let { predicates += "status = ?"; args += it.name }
        filter.from?.let { predicates += "created_at >= ?"; args += Timestamp.from(it.startOfDay()) }
        filter.to?.let { predicates += "created_at < ?"; args += Timestamp.from(it.plusDays(1).startOfDay()) }
        return " WHERE ${predicates.joinToString(" AND ")}" to args
    }

    private fun LocalDate.startOfDay(): Instant = atStartOfDay(VIETNAM_ZONE).toInstant()

    private fun ResultSet.toSummary() = AdminCustomerSummary(
        id = getLong("id"),
        fullName = getString("full_name"),
        phoneNumber = getString("phone_number"),
        email = getString("email"),
        status = UserStatus.valueOf(getString("status")),
        isFlagged = getBoolean("is_flagged"),
        totalTrips = getInt("total_trips"),
        totalCancelled = getInt("total_cancelled"),
        cancelRate30d = getDouble("cancel_rate_30d"),
        averageRating = getObject("average_rating", Double::class.javaObjectType),
        walletBalance = getLong("wallet_balance"),
        lastLoginAt = getTimestamp("last_login_at")?.toLocalDateTime(),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private fun ResultSet.toRideSummary() = RideSummaryResponse(
        id = getLong("id"),
        pickupAddress = getString("pickup_address"),
        dropoffAddress = getString("dropoff_address"),
        type = RideType.valueOf(getString("type")),
        status = RideStatus.valueOf(getString("status")),
        estimatedFare = getLong("fare_estimate"),
        finalFare = getObject("final_fare", Long::class.javaObjectType),
        scheduledAt = getTimestamp("scheduled_at")?.toInstant(),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    private companion object {
        val VIETNAM_ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

        val SUMMARY_COLUMNS = """
            c.id, c.full_name, c.phone_number, c.email, c.status, COALESCE(c.is_flagged, false) AS is_flagged,
            stats.total_trips, stats.total_cancelled, stats.cancel_rate_30d, c.average_rating,
            COALESCE(c.wallet_balance, 0) AS wallet_balance, c.last_login_at, c.created_at
        """.trimIndent()

        val RIDE_STATS_JOIN = """
            CROSS JOIN LATERAL (
                SELECT COUNT(*) AS total_trips,
                       COUNT(*) FILTER (WHERE status = 'CANCELLED') AS total_cancelled,
                       COALESCE(
                           (COUNT(*) FILTER (WHERE recent AND status = 'CANCELLED' AND cancelled_by_role = 'CUSTOMER'))::double precision
                           / NULLIF(COUNT(*) FILTER (WHERE recent), 0),
                           0.0
                       ) AS cancel_rate_30d
                FROM (
                    SELECT status, cancelled_by_role,
                           created_at BETWEEN CURRENT_TIMESTAMP - INTERVAL '30 days' AND CURRENT_TIMESTAMP AS recent
                    FROM rides WHERE customer_id = c.id
                ) customer_rides
            ) stats
        """.trimIndent()
    }
}

data class CustomerDetailFields(val dateOfBirth: LocalDate?, val gender: String?, val defaultPaymentMethod: String?)
