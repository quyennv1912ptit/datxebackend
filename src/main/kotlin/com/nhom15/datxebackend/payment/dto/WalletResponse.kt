package com.nhom15.datxebackend.payment.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

data class WalletResponse(
    @JsonProperty("income_balance")
    val incomeBalance: Long,
    @JsonProperty("wallet_transactions")
    val transactions: List<WalletTransactionResponse>,
)

data class WalletTransactionResponse(
    val id: Long,
    @JsonProperty("ride_id")
    val rideId: Long?,
    val type: String,
    val amount: Long,
    @JsonProperty("balance_after")
    val balanceAfter: Long?,
    val description: String?,
    @JsonProperty("created_at")
    val createdAt: Instant,
)
