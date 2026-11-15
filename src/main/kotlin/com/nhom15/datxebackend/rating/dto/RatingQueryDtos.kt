package com.nhom15.datxebackend.rating.dto

import java.time.LocalDateTime

data class RatingReceivedItem(
    val stars: Int,
    val comment: String?,
    val tags: List<String> = emptyList(),
    val createdAt: LocalDateTime,
)

data class RatingReceivedResponse(
    val averageRating: Double?,
    val totalRatings: Long,
    val ratings: List<RatingReceivedItem>,
    val page: Int,
    val size: Int,
    val totalPages: Int,
    val hasNext: Boolean,
)

data class RatingVisibilityRequest(
    val hidden: Boolean,
    val reason: String? = null,
)

data class AdminRatingItem(
    val id: Long,
    val rideId: Long,
    val direction: String,
    val raterId: Long,
    val raterName: String?,
    val rateeId: Long,
    val rateeName: String?,
    val stars: Int,
    val comment: String?,
    val tags: List<String>,
    val tip: Long,
    val createdAt: LocalDateTime,
    val hidden: Boolean,
    val hiddenAt: LocalDateTime?,
    val hiddenBy: Long?,
    val hiddenReason: String?,
)