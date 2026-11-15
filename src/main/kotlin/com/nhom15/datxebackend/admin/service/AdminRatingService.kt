package com.nhom15.datxebackend.admin.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.rating.dto.AdminRatingItem
import com.nhom15.datxebackend.rating.dto.RatingVisibilityRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class AdminRatingService(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val selectSql = """
        SELECT
            r.id,
            r.ride_id,
            r.direction,
            r.rater_id,
            COALESCE(rc.full_name, rd.full_name) AS rater_name,
            r.ratee_id,
            COALESCE(td.full_name, tc.full_name) AS ratee_name,
            r.stars,
            r.comment,
            r.tags,
            COALESCE(r.tip, 0) AS tip,
            r.created_at,
            r.is_hidden,
            r.hidden_at,
            r.hidden_by,
            r.hidden_reason
        FROM ratings r
        LEFT JOIN customers rc
            ON r.direction = 'CUSTOMER_TO_DRIVER'
            AND rc.id = r.rater_id
        LEFT JOIN drivers rd
            ON r.direction = 'DRIVER_TO_CUSTOMER'
            AND rd.id = r.rater_id
        LEFT JOIN drivers td
            ON r.direction = 'CUSTOMER_TO_DRIVER'
            AND td.id = r.ratee_id
        LEFT JOIN customers tc
            ON r.direction = 'DRIVER_TO_CUSTOMER'
            AND tc.id = r.ratee_id
    """.trimIndent()

    @Transactional(readOnly = true)
    fun list(
        actor: AuthUser,
        rideId: Long? = null,
        direction: String?,
        stars: Int?,
        raterId: Long?,
        rateeId: Long?,
        from: LocalDate?,
        to: LocalDate?,
        hidden: Boolean?,
        page: Int,
        size: Int,
    ): PageResponse<AdminRatingItem> {
        requireAdmin(actor)
        validateFilters(stars, from, to, page, size)

        val (where, params) = buildFilters(
            rideId, direction, stars, raterId, rateeId, from, to, hidden,
        )

        val total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ratings r $where",
            Long::class.java,
            *params.toTypedArray(),
        ) ?: 0L

        val items = jdbcTemplate.query(
            """
            $selectSql
            $where
            ORDER BY r.created_at DESC, r.id DESC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            { rs, _ -> mapRating(rs) },
            *(params + listOf(size, page * size)).toTypedArray(),
        )

        val totalPages = if (total == 0L) {
            0
        } else {
            ((total + size - 1) / size).toInt()
        }

        return PageResponse(
            content = items,
            page = page,
            size = size,
            totalElements = total,
            totalPages = totalPages,
            hasNext = page + 1 < totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun detail(actor: AuthUser, id: Long): AdminRatingItem {
        requireAdmin(actor)

        if (id <= 0) {
            throw BadRequestException(message = "ratingId không hợp lệ")
        }

        return jdbcTemplate.query(
            "$selectSql WHERE r.id = ?",
            { rs, _ -> mapRating(rs) },
            id,
        ).firstOrNull() ?: throw NotFoundException(message = "Không tìm thấy đánh giá id=$id")
    }

    @Transactional
    fun updateVisibility(
        actor: AuthUser,
        id: Long,
        request: RatingVisibilityRequest,
    ): AdminRatingItem {
        requireAdmin(actor)

        if (id <= 0) {
            throw BadRequestException(message = "ratingId không hợp lệ")
        }

        val reason = request.reason?.trim()?.takeIf(String::isNotEmpty)

        if ((reason?.length ?: 0) > 500) {
            throw BadRequestException(message = "Lý do ẩn tối đa 500 ký tự")
        }

        val updated = jdbcTemplate.update(
            """
            UPDATE ratings
            SET is_hidden = ?,
                hidden_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                hidden_by = CASE WHEN ? THEN ? ELSE NULL END,
                hidden_reason = CASE WHEN ? THEN ? ELSE NULL END
            WHERE id = ?
            """.trimIndent(),
            request.hidden,
            request.hidden,
            request.hidden,
            actor.id,
            request.hidden,
            reason,
            id,
        )

        if (updated == 0) {
            throw NotFoundException(message = "Không tìm thấy đánh giá id=$id để cập nhật")
        }

        // Không cập nhật average_rating tại đây:
        // Đánh giá ẩn vẫn phải được tính vào điểm trung bình.
        return detail(actor, id)
    }

    private fun buildFilters(
        rideId: Long?, direction: String?,
        stars: Int?,
        raterId: Long?,
        rateeId: Long?,
        from: LocalDate?,
        to: LocalDate?,
        hidden: Boolean?,
    ): Pair<String, List<Any>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        rideId?.let {
            conditions += "r.ride_id = ?"
            params += it
        }

        direction?.let {
            conditions += "r.direction = ?"
            params += it
        }

        stars?.let {
            conditions += "r.stars = ?"
            params += it
        }

        raterId?.let {
            conditions += "r.rater_id = ?"
            params += it
        }

        rateeId?.let {
            conditions += "r.ratee_id = ?"
            params += it
        }

        from?.let {
            conditions += "r.created_at >= ?"
            params += it.atStartOfDay()
        }

        to?.let {
            conditions += "r.created_at < ?"
            params += it.plusDays(1).atStartOfDay()
        }

        hidden?.let {
            conditions += "r.is_hidden = ?"
            params += it
        }

        val where = if (conditions.isEmpty()) {
            ""
        } else {
            "WHERE " + conditions.joinToString(" AND ")
        }

        return where to params
    }

    private fun mapRating(rs: java.sql.ResultSet) = AdminRatingItem(
        id = rs.getLong("id"),
        rideId = rs.getLong("ride_id"),
        direction = rs.getString("direction"),
        raterId = rs.getLong("rater_id"),
        raterName = rs.getString("rater_name"),
        rateeId = rs.getLong("ratee_id"),
        rateeName = rs.getString("ratee_name"),
        stars = rs.getInt("stars"),
        comment = rs.getString("comment"),
        tags = rs.getString("tags")
            ?.split(",")
            ?.filter(String::isNotBlank)
            ?: emptyList(),
        tip = rs.getLong("tip"),
        createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
        hidden = rs.getBoolean("is_hidden"),
        hiddenAt = rs.getTimestamp("hidden_at")?.toLocalDateTime(),
        hiddenBy = rs.getLong("hidden_by").let {
            if (rs.wasNull()) null else it
        },
        hiddenReason = rs.getString("hidden_reason"),
    )

    private fun validateFilters(
        stars: Int?,
        from: LocalDate?,
        to: LocalDate?,
        page: Int,
        size: Int,
    ) {
        if (stars != null && stars !in 1..5) {
            throw BadRequestException(message = "stars phải từ 1 đến 5")
        }

        if (from != null && to != null && from.isAfter(to)) {
            throw BadRequestException(message = "Ngày bắt đầu không được sau ngày kết thúc")
        }

        if (page < 0 || size !in 1..100) {
            throw BadRequestException(message = "page phải >= 0 và size phải từ 1 đến 100")
        }
    }

    private fun requireAdmin(actor: AuthUser) {
        if (actor.role != Role.ADMIN) {
            throw ForbiddenException(message = "Chỉ admin được quản lý đánh giá")
        }
    }
}