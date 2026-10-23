package com.nhom15.datxebackend.rating.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.rating.dto.CreateRideRatingRequest
import com.nhom15.datxebackend.rating.dto.RideRatingResponse
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RideRatingService(
    private val rideRepository: RideRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun rate(actor: AuthUser, rideId: Long, request: CreateRideRatingRequest): RideRatingResponse {
        if (request.tip > 0) {
            throw BadRequestException(message = "Tip chưa khả dụng vì chưa có luồng thanh toán an toàn")
        }
        if (actor.role !in setOf(Role.CUSTOMER, Role.DRIVER)) {
            throw ForbiddenException(message = "Chỉ khách hàng hoặc tài xế được đánh giá chuyến")
        }
        if (rideId <= 0) throw BadRequestException(message = "rideId phải lớn hơn 0")
        val ride = rideRepository.findById(rideId).orElseThrow { NotFoundException(ErrorCode.RIDE_NOT_FOUND) }
        val (direction, rateeId, driverId, customerId) = when (actor.role) {
            Role.CUSTOMER -> {
                if (ride.customerId != actor.id) throw NotFoundException(ErrorCode.RIDE_NOT_FOUND)
                val assignedDriverId = ride.driverId
                    ?: throw BadRequestException(message = "Chuyến chưa có tài xế")
                RatingTarget("CUSTOMER_TO_DRIVER", assignedDriverId, assignedDriverId, ride.customerId)
            }
            Role.DRIVER -> {
                if (ride.driverId != actor.id) throw NotFoundException(ErrorCode.RIDE_NOT_FOUND)
                if (request.tip > 0) throw BadRequestException(message = "Tài xế không thể gửi tiền boa trong đánh giá")
                RatingTarget("DRIVER_TO_CUSTOMER", ride.customerId, ride.driverId, ride.customerId)
            }
            else -> throw ForbiddenException(message = "Chỉ khách hàng hoặc tài xế được đánh giá chuyến")
        }
        if (ride.status != RideStatus.COMPLETED) {
            throw BadRequestException(message = "Chỉ đánh giá được chuyến đã hoàn thành")
        }
        val tags = request.tags.orEmpty().map(String::trim).filter(String::isNotEmpty).distinct()
        val storedTags = tags.joinToString(",").takeIf(String::isNotEmpty)
        if (
            request.stars !in 1..5 || request.tip < 0 || (request.comment?.length ?: 0) > 2000 ||
            tags.size > 10 || tags.any { it.length > 50 } || (storedTags?.length ?: 0) > 255
        ) {
            throw BadRequestException(message = "Thông tin đánh giá không hợp lệ")
        }
        try {
            jdbcTemplate.update(
                """INSERT INTO ratings (ride_id, direction, ratee_id, rater_id, stars, comment, tags, tip)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".trimIndent(),
                rideId, direction, rateeId, actor.id, request.stars,
                request.comment?.trim()?.takeIf(String::isNotEmpty), storedTags, request.tip,
            )
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException(ErrorCode.RATING_ALREADY_EXISTS)
        }
        val targetTable = if (direction == "CUSTOMER_TO_DRIVER") "drivers" else "customers"
        val updated = jdbcTemplate.update(
            """UPDATE $targetTable
               SET average_rating = (SELECT AVG(stars)::double precision FROM ratings WHERE ratee_id = ? AND direction = ?),
                   updated_at = CURRENT_TIMESTAMP
               WHERE id = ?""".trimIndent(),
            rateeId,
            direction,
            rateeId,
        )
        if (updated == 0) throw NotFoundException(ErrorCode.USER_NOT_FOUND)
        return RideRatingResponse(
            rideId = rideId,
            direction = direction,
            driverId = driverId,
            customerId = customerId,
            stars = request.stars,
            comment = request.comment?.trim()?.takeIf(String::isNotEmpty),
            tip = request.tip,
            tags = tags,
        )
    }

    private data class RatingTarget(
        val direction: String,
        val rateeId: Long,
        val driverId: Long?,
        val customerId: Long?,
    )
}
