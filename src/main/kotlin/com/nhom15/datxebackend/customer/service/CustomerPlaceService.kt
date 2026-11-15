package com.nhom15.datxebackend.customer.service

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.util.GeoUtils.distanceKm
import com.nhom15.datxebackend.customer.dto.CustomerPlaceResponse
import com.nhom15.datxebackend.customer.dto.ReportPlaceRequest
import com.nhom15.datxebackend.customer.dto.ReportPlaceResponse
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.support.ticket.CreateTicketRequest
import com.nhom15.datxebackend.support.ticket.TicketService
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import kotlin.math.roundToInt

/**
 * Gợi ý địa điểm dựa trên dữ liệu chuyến đi của khách:
 * - `recent`: các điểm đón/đến khách đã dùng, mới nhất trước.
 * - `popular`: điểm đến được nhiều khách chọn, kèm khoảng cách tới vị trí hiện tại.
 */
@Service
class CustomerPlaceService(
    private val rides: RideRepository,
    private val jdbc: JdbcTemplate,
    private val tickets: TicketService,
) {
    fun recent(actor: AuthUser, kind: String, proximity: GeoPoint?, limit: Int): List<CustomerPlaceResponse> {
        val pickup = kind == "pickup"
        val page = rides.findByCustomerIdOrderByCreatedAtDesc(actor.id, PageRequest.of(0, RECENT_SCAN_SIZE))
        val seen = HashSet<String>()
        val result = ArrayList<CustomerPlaceResponse>(limit)
        for (ride in page.content) {
            val location = if (pickup) ride.pickup else ride.dropoff
            val address = location.address.trim()
            if (address.isBlank()) continue
            if (!seen.add(address.lowercase())) continue
            val point = location.geoPoint
            result += CustomerPlaceResponse(
                id = "recent:${ride.id}:$kind",
                name = displayName(address),
                address = address,
                provider = "history",
                coordinate = GeoPoint(point.lat, point.lng),
                distanceMeters = proximity?.let { meters(it, point) },
            )
            if (result.size >= limit) break
        }
        return result
    }

    fun popular(proximity: GeoPoint?, limit: Int): List<CustomerPlaceResponse> {
        val rows = jdbc.query(
            """
            SELECT dropoff_address, dropoff_lat, dropoff_lng, COUNT(*) AS uses
            FROM rides
            WHERE status = 'COMPLETED'
              AND dropoff_address IS NOT NULL
              AND dropoff_lat IS NOT NULL
              AND dropoff_lng IS NOT NULL
              AND created_at >= NOW() - INTERVAL '180 days'
            GROUP BY dropoff_address, dropoff_lat, dropoff_lng
            ORDER BY uses DESC
            LIMIT $POPULAR_SCAN_SIZE
            """.trimIndent(),
        ) { row, _ ->
            PopularRow(
                address = row.getString("dropoff_address").trim(),
                point = GeoPoint(row.getDouble("dropoff_lat"), row.getDouble("dropoff_lng")),
                uses = row.getInt("uses"),
            )
        }

        return rows.asSequence()
            .filter { it.address.isNotBlank() }
            .filter { proximity == null || meters(proximity, it.point) <= MAX_POPULAR_DISTANCE_METERS }
            .take(limit)
            .map {
                CustomerPlaceResponse(
                    id = "popular:${it.address.lowercase()}:${it.point.lat},${it.point.lng}",
                    name = displayName(it.address),
                    address = it.address,
                    provider = "popular",
                    coordinate = it.point,
                    distanceMeters = proximity?.let { origin -> meters(origin, it.point) },
                    useCount = it.uses,
                )
            }
            .toList()
    }

    fun report(actor: AuthUser, request: ReportPlaceRequest): ReportPlaceResponse {
        val name = request.name.trim()
        val address = request.address.trim()
        val reason = request.reason.trim()
        if (name.isEmpty() || address.isEmpty()) throw BadRequestException(message = "Thiếu tên hoặc địa chỉ cần báo lỗi")
        if (reason.length !in 5..500) throw BadRequestException(message = "Lý do cần từ 5 đến 500 ký tự")
        val description = buildString {
            appendLine("Báo lỗi địa chỉ")
            request.placeId?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("Mã địa điểm: $it") }
            appendLine("Tên: $name")
            appendLine("Địa chỉ: $address")
            request.lat?.let { lat -> request.lng?.let { lng -> appendLine("Tọa độ: $lat, $lng") } }
            append("Lý do: $reason")
        }.take(4_000)
        val ticket = tickets.create(actor, CreateTicketRequest(category = "OTHER", description = description))
        return ReportPlaceResponse(ticketId = ticket.id, code = ticket.code)
    }

    private fun meters(origin: GeoPoint, target: GeoPoint) =
        (distanceKm(origin, target) * 1_000).roundToInt()

    private fun displayName(address: String): String {
        val parts = address.split(',').map(String::trim).filter(String::isNotEmpty)
        if (parts.isEmpty()) return address
        val first = parts[0]
        return if (first.length >= 3 || first.any { it.isLetter() }) first else parts.take(2).joinToString(", ")
    }

    private data class PopularRow(val address: String, val point: GeoPoint, val uses: Int)

    private companion object {
        const val RECENT_SCAN_SIZE = 30
        const val POPULAR_SCAN_SIZE = 200
        const val MAX_POPULAR_DISTANCE_METERS = 50_000
    }
}
