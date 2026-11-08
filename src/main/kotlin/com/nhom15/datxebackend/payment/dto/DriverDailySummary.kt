package com.nhom15.datxebackend.payment.dto

import java.time.LocalDate

data class DriverDailySummary(
    val date: LocalDate,
    val fullName: String?,
    val avatarUrl: String?,
    val completedTrips: Long,
    val income: Long,
)
