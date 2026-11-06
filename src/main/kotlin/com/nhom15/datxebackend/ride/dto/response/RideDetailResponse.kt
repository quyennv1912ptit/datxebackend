package com.nhom15.datxebackend.ride.dto.response

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.entity.RideActorRole
import java.math.BigDecimal
import java.time.Instant

data class RideCustomerResponse(
    val id: Long,
    val fullName: String?,
    val phoneNumber: String?,
    val avatarUrl: String?,
    val averageRating: Double? = null,
    val email: String? = null,
)

data class RideDriverResponse(
    val id: Long,
    val fullName: String?,
    val phoneNumber: String,
    val avatarUrl: String?,
    val averageRating: Double? = null,
    val yearsOfExperience: Int? = null,
    val licenseClass: String? = null,
    val licenseVerified: Boolean = false,
    val profileVerified: Boolean = false,
)

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
    val licensePlate: String? = null,
    val vehicleType: VehicleType? = null,
    val transmission: Transmission? = null,
    val driver: RideDriverResponse? = null,
    val customer: RideCustomerResponse? = null,
    val payment: com.nhom15.datxebackend.payment.dto.RidePaymentResponse? = null,
)
