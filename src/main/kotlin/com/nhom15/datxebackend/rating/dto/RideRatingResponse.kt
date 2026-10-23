package com.nhom15.datxebackend.rating.dto

data class RideRatingResponse(
    val rideId: Long,
    val direction: String,
    val driverId: Long?,
    val customerId: Long?,
    val stars: Int,
    val comment: String?,
    val tip: Long,
    val tags: List<String> = emptyList(),
)
