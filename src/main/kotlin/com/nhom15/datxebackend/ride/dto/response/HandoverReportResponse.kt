package com.nhom15.datxebackend.ride.dto.response

import com.nhom15.datxebackend.ride.entity.HandoverPhase
import java.time.Instant

data class HandoverReportResponse(
    val id: Long,
    val phase: HandoverPhase,
    val odometer: Long,
    val fuelLevel: Int?,
    val notes: String?,
    val confirmedByCustomer: Boolean,
    val photos: List<HandoverPhotoResponse>,
    val createdAt: Instant,
)
