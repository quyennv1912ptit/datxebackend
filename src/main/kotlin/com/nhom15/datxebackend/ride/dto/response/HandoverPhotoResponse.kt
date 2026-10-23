package com.nhom15.datxebackend.ride.dto.response

import com.nhom15.datxebackend.ride.entity.HandoverPhotoAngle
import java.time.Instant

data class HandoverPhotoResponse(
    val id: Long,
    val angle: HandoverPhotoAngle,
    val fileUrl: String,
    val createdAt: Instant,
)
