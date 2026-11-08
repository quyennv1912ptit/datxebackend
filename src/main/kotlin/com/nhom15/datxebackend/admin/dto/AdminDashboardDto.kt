package com.nhom15.datxebackend.admin.dto

import java.time.Instant
import java.time.LocalDate

data class AdminDashboardDto(
    val gmv: Long,
    val revenue: Long,
    val promoCost: Long,
    val grossProfit: Long,
    val tripCount: Long,
    val previousTripCount: Long,
    val daily: List<AdminDashboardDay>,
    val paymentMix: AdminDashboardPaymentMix,
    val wallets: AdminDashboardWallets,
    val anomalies: List<AdminDashboardAnomaly>,
)

data class AdminDashboardDay(val day: LocalDate, val gmv: Long = 0, val revenue: Long = 0)
data class AdminDashboardPaymentMix(val cash: Long, val wallet: Long, val other: Long)
data class AdminDashboardWallets(val driverCash: Long, val customer: Long, val driverCredit: Long, val pendingWithdrawals: Long)
data class AdminDashboardAnomaly(val kind: String, val subjectId: Long, val value: Long, val occurredAt: Instant)
