package com.nhom15.datxebackend.admin.service

import com.nhom15.datxebackend.admin.dto.*
import com.nhom15.datxebackend.common.exception.BadRequestException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

@Service
class AdminDashboardService(private val jdbc: JdbcTemplate, private val clock: Clock) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun summary(days: Int): AdminDashboardDto {
        if (days != 7 && days != 30) throw BadRequestException(message = "Khoảng thống kê phải là 7 hoặc 30 ngày")
        val asOf = clock.instant().atZone(zone)
        val firstDay = asOf.toLocalDate().minusDays(days.toLong() - 1)
        val start = Timestamp.from(firstDay.atStartOfDay(zone).toInstant())
        val end = Timestamp.from(asOf.toInstant())
        val previousStart = Timestamp.from(firstDay.minusDays(days.toLong()).atStartOfDay(zone).toInstant())
        val previousEnd = Timestamp.from(asOf.minusDays(days.toLong()).toInstant())

        val trips = jdbc.queryForObject(
            """SELECT COUNT(*) FILTER (WHERE created_at >= ?) AS current_count,
                      COUNT(*) FILTER (WHERE created_at < ?) AS previous_count
               FROM rides WHERE created_at >= ? AND created_at < ?""",
            { row, _ -> row.getLong("current_count") to row.getLong("previous_count") },
            start, previousEnd, previousStart, end,
        )
        val daily = jdbc.query(
            """SELECT (COALESCE(r.completed_at, r.created_at) AT TIME ZONE '${zone.id}')::date AS day,
                      SUM($grossFare) AS gmv,
                      SUM(COALESCE(r.discount_amount, 0)) AS promo_cost,
                      SUM(CASE p.method WHEN 'CASH' THEN -ledger.amount
                                        WHEN 'WALLET' THEN p.amount - ledger.amount ELSE 0 END) AS revenue,
                      COUNT(*) FILTER (WHERE COALESCE(p.method, r.payment_method) = 'CASH') AS cash,
                      COUNT(*) FILTER (WHERE COALESCE(p.method, r.payment_method) = 'WALLET') AS wallet,
                      COUNT(*) FILTER (WHERE COALESCE(p.method, r.payment_method) NOT IN ('CASH', 'WALLET')) AS other
               FROM rides r
               LEFT JOIN payments p ON p.ride_id = r.id
               LEFT JOIN LATERAL (
                   SELECT t.amount FROM wallet_transactions t
                   WHERE t.ride_id = r.id AND t.driver_id = r.driver_id AND t.status = 'SUCCESS'
                     AND ((p.method = 'CASH' AND t.type = 'COMMISSION_FEE' AND t.wallet_type = 'CREDIT')
                       OR (p.method = 'WALLET' AND t.type = 'TRIP_EARNING' AND t.wallet_type = 'CASH'))
                   ORDER BY t.id DESC LIMIT 1
               ) ledger ON TRUE
               WHERE r.status = 'COMPLETED' AND COALESCE(r.completed_at, r.created_at) >= ?
                 AND COALESCE(r.completed_at, r.created_at) < ?
               GROUP BY 1""",
            { row, _ -> DailyTotals(
                AdminDashboardDay(row.getObject("day", LocalDate::class.java), row.getLong("gmv"), row.getLong("revenue")),
                row.getLong("promo_cost"), row.getLong("cash"), row.getLong("wallet"), row.getLong("other"),
            ) }, start, end,
        ).associateBy { it.point.day }
        val wallets = jdbc.queryForObject(
            """SELECT COALESCE(SUM(balance) FILTER (WHERE wallet_type = 'CASH'), 0) AS driver_cash,
                      COALESCE(SUM(balance) FILTER (WHERE wallet_type = 'CUSTOMER'), 0) AS customer,
                      COALESCE(SUM(balance) FILTER (WHERE wallet_type = 'CREDIT' AND balance > 0), 0) AS driver_credit,
                      (SELECT COALESCE(SUM(amount), 0) FROM withdrawal_requests WHERE status = 'PENDING') AS pending
               FROM wallets""",
            { row, _ -> AdminDashboardWallets(row.getLong("driver_cash"), row.getLong("customer"), row.getLong("driver_credit"), row.getLong("pending")) },
        )
        val anomalies = jdbc.query(
            """SELECT * FROM (
                   SELECT 'HIGH_FARE' AS kind, r.id AS subject_id, $grossFare AS value,
                          COALESCE(r.completed_at, r.created_at) AS occurred_at
                   FROM rides r WHERE r.status = 'COMPLETED' AND COALESCE(r.completed_at, r.created_at) >= ?
                     AND COALESCE(r.completed_at, r.created_at) < ? AND $grossFare >= 1000000
                   UNION ALL
                   SELECT 'REPEATED_CANCELLATIONS', customer_id, COUNT(*), MAX(cancelled_at)
                   FROM rides WHERE status = 'CANCELLED' AND cancelled_by_role = 'CUSTOMER'
                     AND cancelled_at >= ? AND cancelled_at < ?
                   GROUP BY customer_id HAVING COUNT(*) >= 3
               ) alerts ORDER BY occurred_at DESC, kind, subject_id DESC LIMIT 5""",
            { row, _ -> AdminDashboardAnomaly(row.getString("kind"), row.getLong("subject_id"), row.getLong("value"), row.getTimestamp("occurred_at").toInstant()) },
            start, end, start, end,
        )
        val totals = daily.values
        val revenue = totals.sumOf { it.point.revenue }
        val promoCost = totals.sumOf { it.promoCost }
        return AdminDashboardDto(
            gmv = totals.sumOf { it.point.gmv }, revenue = revenue, promoCost = promoCost, grossProfit = revenue - promoCost,
            tripCount = trips.first, previousTripCount = trips.second,
            daily = List(days) { index ->
                val day = firstDay.plusDays(index.toLong())
                daily[day]?.point ?: AdminDashboardDay(day)
            },
            paymentMix = AdminDashboardPaymentMix(totals.sumOf { it.cash }, totals.sumOf { it.wallet }, totals.sumOf { it.other }),
            wallets = wallets, anomalies = anomalies,
        )
    }

    private data class DailyTotals(val point: AdminDashboardDay, val promoCost: Long, val cash: Long, val wallet: Long, val other: Long)

    companion object {
        private val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        // Voucher rides persist the amount payable as final_fare and the pre-voucher fare as fare_estimate.
        private const val grossFare = "(CASE WHEN r.discount_amount > 0 THEN r.fare_estimate ELSE COALESCE(r.final_fare, r.fare_estimate) END)"
    }
}
