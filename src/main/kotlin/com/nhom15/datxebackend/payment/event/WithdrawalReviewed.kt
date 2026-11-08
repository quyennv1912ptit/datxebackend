package com.nhom15.datxebackend.payment.event

import com.nhom15.datxebackend.payment.entity.WithdrawalStatus

data class WithdrawalReviewed(val requestId: Long, val driverId: Long, val amount: Long,
    val status: WithdrawalStatus, val rejectReason: String?)
