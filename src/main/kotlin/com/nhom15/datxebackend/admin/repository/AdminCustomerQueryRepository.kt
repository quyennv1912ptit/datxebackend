package com.nhom15.datxebackend.admin.repository

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
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM customers$where", Long::class.java, *args.toTypedArray()) ?: 0L
    }

    fun findPage(filter: AdminCustomerFilter): List<AdminCustomerSummary> {
        val (where, args) = customerWhere(filter)
        val sortColumn = when (filter.sort) {
            "createdAt" -> "created_at"
            "totalTrips" -> "total_trips"
            "cancelRate30d" -> "cancel_rate_30d"
            else -> throw IllegalArgumentException("sort must be validated by service")
        }
        return jdbcTemplate.query(
            """SELECT id, full_name, phone_number, email, status, COALESCE(is_flagged, false) AS is_flagged,
                      COALESCE(total_trips, 0) AS total_trips, COALESCE(total_cancelled, 0) AS total_cancelled,
                      COALESCE(cancel_rate_30d, 0) AS cancel_rate_30d, average_rating,
                      COALESCE(wallet_balance, 0) AS wallet_balance, last_login_at, created_at
               FROM customers$where ORDER BY $sortColumn DESC NULLS LAST, id DESC LIMIT ? OFFSET ?""".trimIndent(),
            { row, _ -> row.toSummary() },
            *(args + listOf(filter.size, filter.page.toLong() * filter.size)).toTypedArray(),
        )
    }

    fun findById(id: Long): AdminCustomerSummary? = jdbcTemplate.query(
        """SELECT id, full_name, phone_number, email, status, COALESCE(is_flagged, false) AS is_flagged,
                  COALESCE(total_trips, 0) AS total_trips, COALESCE(total_cancelled, 0) AS total_cancelled,
                  COALESCE(cancel_rate_30d, 0) AS cancel_rate_30d, average_rating,
                  COALESCE(wallet_balance, 0) AS wallet_balance, last_login_at, created_at
           FROM customers WHERE id = ? AND deleted_at IS NULL""".trimIndent(),
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

    fun countWalletTransactions(customerId: Long): Long = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM wallet_transactions WHERE customer_id = ?",
        Long::class.java,
        customerId,
    ) ?: 0L

    fun findWalletTransactions(customerId: Long, page: Int, size: Int): List<AdminWalletTransaction> = jdbcTemplate.query(
        """SELECT id, ride_id, amount, type, status, description, wallet_type, balance_after, created_at
           FROM wallet_transactions WHERE customer_id = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?""".trimIndent(),
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
        customerId,
        size,
        page.toLong() * size,
    )

    private fun customerWhere(filter: AdminCustomerFilter): Pair<String, List<Any>> {
        val predicates = mutableListOf("deleted_at IS NULL")
        val args = mutableListOf<Any>()
        filter.status?.let { predicates += "status = ?"; args += it.name }
        filter.flagged?.let { predicates += "COALESCE(is_flagged, false) = ?"; args += it }
        filter.q?.trim()?.takeIf(String::isNotEmpty)?.let {
            predicates += "(full_name ILIKE ? OR phone_number ILIKE ? OR email ILIKE ?)"
            val pattern = "%$it%"
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
    }
}

data class CustomerDetailFields(val dateOfBirth: LocalDate?, val gender: String?, val defaultPaymentMethod: String?)
