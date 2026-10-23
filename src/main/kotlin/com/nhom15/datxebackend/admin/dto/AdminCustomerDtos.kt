package com.nhom15.datxebackend.admin.dto

import com.nhom15.datxebackend.common.enums.UserStatus
import com.nhom15.datxebackend.ride.dto.response.RideSummaryResponse
import com.nhom15.datxebackend.vehicle.dto.VehicleResponse
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

data class AdminCustomerFilter(
    val q: String? = null,
    val status: UserStatus? = null,
    val flagged: Boolean? = null,
    val page: Int = 0,
    val size: Int = 20,
    val sort: String = "createdAt",
)

data class AdminCustomerSummary(
    val id: Long,
    val fullName: String?,
    val phoneNumber: String?,
    val email: String?,
    val status: UserStatus,
    val isFlagged: Boolean,
    val totalTrips: Int,
    val totalCancelled: Int,
    val cancelRate30d: Double,
    val averageRating: Double?,
    val walletBalance: Long,
    val lastLoginAt: LocalDateTime?,
    val createdAt: Instant,
)

data class AdminCustomerDetail(
    val id: Long,
    val fullName: String?,
    val phoneNumber: String?,
    val email: String?,
    val status: UserStatus,
    val isFlagged: Boolean,
    val totalTrips: Int,
    val totalCancelled: Int,
    val cancelRate30d: Double,
    val averageRating: Double?,
    val walletBalance: Long,
    val lastLoginAt: LocalDateTime?,
    val createdAt: Instant,
    val dateOfBirth: LocalDate?,
    val gender: String?,
    val defaultPaymentMethod: String?,
    val vehicles: List<VehicleResponse>,
    val recentRides: List<RideSummaryResponse>,
)

data class AdminCustomerRidesFilter(
    val status: String? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val page: Int = 0,
    val size: Int = 20,
)

data class AdminWalletTransaction(
    val id: Long,
    val rideId: Long?,
    val amount: Long,
    val type: String,
    val status: String?,
    val description: String?,
    val walletType: String,
    val balanceAfter: Long?,
    val createdAt: Instant,
)

data class UpdateAdminCustomerStatusRequest(
    val status: UserStatus,
    @field:Size(max = 1000)
    val reason: String? = null,
)

data class UpdateAdminCustomerFlagRequest(
    val flagged: Boolean,
    @field:Size(max = 1000)
    val note: String? = null,
)

data class AdminCustomerStatusResponse(
    val id: Long,
    val status: UserStatus,
)

data class AdminCustomerFlagResponse(
    val id: Long,
    val flagged: Boolean,
)
