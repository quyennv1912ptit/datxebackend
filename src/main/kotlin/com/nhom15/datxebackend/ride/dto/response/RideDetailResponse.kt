package com.nhom15.datxebackend.ride.dto.response

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.entity.RideActorRole
import java.math.BigDecimal
import java.time.Instant

data class RideDetailResponse(
    val id: Long,
    val customerId: Long,
    val driverId: Long?,
    val vehicleId: Long,
    val type: RideType,
    val scheduledAt: Instant?,
    val pickupAddress: String?,
    val pickup: GeoPointResponse,
    val dropoffAddress: String?,
    val dropoff: GeoPointResponse,
    val distanceKm: BigDecimal?,
    val estimatedFare: Long,
    val finalFare: Long?,
    val status: RideStatus,
    val cancelReason: String?,
    val cancelledBy: Long?,
    val statusHistory: List<RideStatusHistoryResponse>,
    val handoverReports: List<HandoverReportResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val acceptedAt: Instant? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val cancelledAt: Instant? = null,
    val cancelledByRole: RideActorRole? = null,
    val discountAmount: Long? = null,
    val unreadChatCount: Long = 0,
    val pickupNote: String? = null,
    val route: com.nhom15.datxebackend.routing.model.SavedRideRoute? = null,
)
