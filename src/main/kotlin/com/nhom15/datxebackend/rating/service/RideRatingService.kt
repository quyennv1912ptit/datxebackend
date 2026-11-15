package com.nhom15.datxebackend.rating.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.notification.dto.RatingReceivedNotificationPayload
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.rating.dto.*
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

@Service
class RideRatingService(
    private val rideRepository: RideRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val notifications: NotificationPort,
) {
    @Transactional
    fun rate(
        actor: AuthUser,
        rideId: Long,
        request: CreateRideRatingRequest,
    ): RideRatingResponse {
        if (request.tip > 0) {
            throw BadRequestException(message = "Tip chưa khả dụng vì chưa có luồng thanh toán an toàn")
        }

        if (actor.role !in setOf(Role.CUSTOMER, Role.DRIVER)) {
            throw ForbiddenException(message = "Chỉ khách hàng hoặc tài xế được đánh giá chuyến")
        }

        if (rideId <= 0) {
            throw BadRequestException(message = "rideId phải lớn hơn 0")
        }

        val ride = rideRepository.findById(rideId)
            .orElseThrow { NotFoundException(ErrorCode.RIDE_NOT_FOUND) }

        val target = when (actor.role) {
            Role.CUSTOMER -> {
                if (ride.customerId != actor.id) {
                    throw NotFoundException(ErrorCode.RIDE_NOT_FOUND)
                }

                val driverId = ride.driverId
                    ?: throw BadRequestException(message = "Chuyến chưa có tài xế")

                RatingTarget(
                    direction = "CUSTOMER_TO_DRIVER",
                    rateeId = driverId,
                    driverId = driverId,
                    customerId = ride.customerId,
                )
            }

            Role.DRIVER -> {
                if (ride.driverId != actor.id) {
                    throw NotFoundException(ErrorCode.RIDE_NOT_FOUND)
                }

                RatingTarget(
                    direction = "DRIVER_TO_CUSTOMER",
                    rateeId = ride.customerId,
                    driverId = ride.driverId,
                    customerId = ride.customerId,
                )
            }

            else -> throw ForbiddenException(
                message = "Chỉ khách hàng hoặc tài xế được đánh giá chuyến",
            )
        }

        if (ride.status != RideStatus.COMPLETED) {
            throw BadRequestException(message = "Chỉ đánh giá được chuyến đã hoàn thành")
        }
        val completedAt = ride.completedAt
            ?: throw BadRequestException(message = "Không xác định được thời điểm hoàn thành chuyến")
        if (Instant.now().isAfter(completedAt.plus(Duration.ofDays(3)))) {
            throw BadRequestException(message = "Đã hết thời hạn đánh giá 3 ngày")
        }

        val tags = request.tags.orEmpty()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

        val storedTags = tags.joinToString(",").takeIf(String::isNotEmpty)

        val alreadyExists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ratings WHERE ride_id = ? AND direction = ?",
            Int::class.java,
            rideId,
            target.direction,
        ) ?: 0
        if (alreadyExists > 0) {
            throw ConflictException(ErrorCode.RATING_ALREADY_EXISTS)
        }

        if (
            request.stars !in 1..5 ||
            request.tip < 0 ||
            (request.comment?.length ?: 0) > 2000 ||
            tags.size > 10 ||
            tags.any { it.length > 50 } ||
            (storedTags?.length ?: 0) > 255
        ) {
            throw BadRequestException(message = "Thông tin đánh giá không hợp lệ")
        }

        try {
            jdbcTemplate.update(
                """
                INSERT INTO ratings (
                    ride_id, direction, ratee_id, rater_id,
                    stars, comment, tags, tip
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                rideId,
                target.direction,
                target.rateeId,
                actor.id,
                request.stars,
                request.comment?.trim()?.takeIf(String::isNotEmpty),
                storedTags,
                request.tip,
            )
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException(ErrorCode.RATING_ALREADY_EXISTS)
        }

        // Cố ý KHÔNG lọc is_hidden:
        // Đánh giá bị ẩn vẫn được tính vào điểm trung bình.
        val targetTable = if (target.direction == "CUSTOMER_TO_DRIVER") {
            "drivers"
        } else {
            "customers"
        }

        val updated = jdbcTemplate.update(
            """
            UPDATE $targetTable
            SET average_rating = (
                SELECT AVG(stars)::double precision
                FROM ratings
                WHERE ratee_id = ? AND direction = ?
            ),
            updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent(),
            target.rateeId,
            target.direction,
            target.rateeId,
        )

        if (updated == 0) {
            throw NotFoundException(ErrorCode.USER_NOT_FOUND)
        }

                val cleanComment = request.comment?.trim()?.takeIf(String::isNotEmpty)
        val recipient = if (target.direction == "CUSTOMER_TO_DRIVER") {
            Recipient.Driver(target.rateeId)
        } else {
            Recipient.Customer(target.rateeId)
        }
        runCatching {
            notifications.send(
                recipient,
                RatingReceivedNotificationPayload(
                    rideId = rideId,
                    stars = request.stars,
                    comment = cleanComment,
                    tags = tags,
                ),
            )
        }

        return RideRatingResponse(
            rideId = rideId,
            direction = target.direction,
            driverId = target.driverId,
            customerId = target.customerId,
            stars = request.stars,
            comment = request.comment?.trim()?.takeIf(String::isNotEmpty),
            tip = request.tip,
            tags = tags,
        )
    }

    @Transactional(readOnly = true)
    fun received(
        actor: AuthUser,
        page: Int,
        size: Int,
    ): RatingReceivedResponse {
        if (actor.role !in setOf(Role.CUSTOMER, Role.DRIVER)) {
            throw ForbiddenException(message = "Không có quyền xem đánh giá")
        }

        validatePage(page, size)

        val (table, direction) = when (actor.role) {
            Role.CUSTOMER -> "customers" to "DRIVER_TO_CUSTOMER"
            Role.DRIVER -> "drivers" to "CUSTOMER_TO_DRIVER"
            else -> throw ForbiddenException(message = "Không có quyền xem đánh giá")
        }

        val average = jdbcTemplate.queryForObject(
            "SELECT average_rating FROM $table WHERE id = ?",
            Double::class.java,
            actor.id,
        )

        // Tổng số và điểm trung bình bao gồm cả đánh giá bị ẩn.
        val total = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ratings
            WHERE ratee_id = ? AND direction = ?
            """.trimIndent(),
            Long::class.java,
            actor.id,
            direction,
        ) ?: 0L

        // Chỉ lọc ẩn ở danh sách nội dung trả cho người dùng.
        val items = jdbcTemplate.query(
            """
            SELECT id, stars, comment, tags, created_at
            FROM ratings
            WHERE ratee_id = ?
              AND direction = ?
              AND is_hidden = false
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            { rs, _ ->
                RatingReceivedItem(
                    stars = rs.getInt("stars"),
                    comment = rs.getString("comment"),
                    tags = rs.getString("tags")
                        ?.split(",")
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        ?: emptyList(),
                    createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
                )
            },
            actor.id,
            direction,
            size,
            page * size,
        )

        val visibleTotal = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM ratings
            WHERE ratee_id = ?
              AND direction = ?
              AND is_hidden = false
            """.trimIndent(),
            Long::class.java,
            actor.id,
            direction,
        ) ?: 0L

        val totalPages = if (visibleTotal == 0L) {
            0
        } else {
            ((visibleTotal + size - 1) / size).toInt()
        }

        return RatingReceivedResponse(
            averageRating = average,
            totalRatings = visibleTotal,
            ratings = items,
            page = page,
            size = size,
            totalPages = totalPages,
            hasNext = page + 1 < totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun myRating(actor: AuthUser, rideId: Long): RideRatingResponse? {
        if (actor.role !in setOf(Role.CUSTOMER, Role.DRIVER)) {
            throw ForbiddenException(message = "Không có quyền xem đánh giá")
        }

        if (rideId <= 0) {
            throw BadRequestException(message = "rideId phải lớn hơn 0")
        }

        val ride = rideRepository.findById(rideId)
            .orElseThrow { NotFoundException(ErrorCode.RIDE_NOT_FOUND) }

        val direction = when (actor.role) {
            Role.CUSTOMER -> {
                if (ride.customerId != actor.id) {
                    throw NotFoundException(ErrorCode.RIDE_NOT_FOUND)
                }
                "CUSTOMER_TO_DRIVER"
            }

            Role.DRIVER -> {
                if (ride.driverId != actor.id) {
                    throw NotFoundException(ErrorCode.RIDE_NOT_FOUND)
                }
                "DRIVER_TO_CUSTOMER"
            }

            else -> throw ForbiddenException(message = "Không có quyền xem đánh giá")
        }

        return jdbcTemplate.query(
            """
            SELECT ride_id, direction, ratee_id, stars, comment, tip, tags
            FROM ratings
            WHERE ride_id = ? AND direction = ? AND rater_id = ?
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                RideRatingResponse(
                    rideId = rs.getLong("ride_id"),
                    direction = rs.getString("direction"),
                    driverId = if (direction == "CUSTOMER_TO_DRIVER") rs.getLong("ratee_id")
                        else ride.driverId,
                    customerId = if (direction == "DRIVER_TO_CUSTOMER") rs.getLong("ratee_id")
                        else ride.customerId,
                    stars = rs.getInt("stars"),
                    comment = rs.getString("comment"),
                    tip = rs.getLong("tip"),
                    tags = rs.getString("tags")
                        ?.split(",")
                        ?.filter(String::isNotBlank)
                        ?: emptyList(),
                )
            },
            rideId,
            direction,
            actor.id,
        ).firstOrNull()
    }

    private fun validatePage(page: Int, size: Int) {
        if (page < 0 || size !in 1..100) {
            throw BadRequestException(message = "page phải >= 0 và size phải từ 1 đến 100")
        }
    }

    private data class RatingTarget(
        val direction: String,
        val rateeId: Long,
        val driverId: Long?,
        val customerId: Long?,
    )
}
