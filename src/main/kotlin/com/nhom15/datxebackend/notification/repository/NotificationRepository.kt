package com.nhom15.datxebackend.notification.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.sql.Timestamp

@Repository
class NotificationRepository(private val jdbcTemplate: JdbcTemplate) {
    fun countUnreadForCustomer(customerId: Long): Long = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM notifications WHERE recipient_customer_id = ? AND read_at IS NULL",
        Long::class.java,
        customerId,
    ) ?: 0L

    fun countUnreadForDriver(driverId: Long): Long = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM notifications WHERE recipient_driver_id = ? AND read_at IS NULL",
        Long::class.java,
        driverId,
    ) ?: 0L

    fun markReadForCustomer(id: Long, customerId: Long, now: Instant): Int = jdbcTemplate.update(
        "UPDATE notifications SET read_at = ? WHERE id = ? AND recipient_customer_id = ? AND read_at IS NULL",
        Timestamp.from(now),
        id,
        customerId,
    )

    fun markReadForDriver(id: Long, driverId: Long, now: Instant): Int = jdbcTemplate.update(
        "UPDATE notifications SET read_at = ? WHERE id = ? AND recipient_driver_id = ? AND read_at IS NULL",
        Timestamp.from(now),
        id,
        driverId,
    )

    fun markAllReadForCustomer(customerId: Long, now: Instant): Int = jdbcTemplate.update(
        "UPDATE notifications SET read_at = ? WHERE recipient_customer_id = ? AND read_at IS NULL",
        Timestamp.from(now),
        customerId,
    )

    fun markAllReadForDriver(driverId: Long, now: Instant): Int = jdbcTemplate.update(
        "UPDATE notifications SET read_at = ? WHERE recipient_driver_id = ? AND read_at IS NULL",
        Timestamp.from(now),
        driverId,
    )
}
