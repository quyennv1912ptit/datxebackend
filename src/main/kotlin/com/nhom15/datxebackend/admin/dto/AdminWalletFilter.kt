package com.nhom15.datxebackend.admin.dto

import com.nhom15.datxebackend.common.exception.BadRequestException

data class AdminWalletFilter(val type: String? = null, val status: String? = null) {
    fun validate() {
        if (type != null && type !in setOf("DEPOSIT", "TRIP_PAYMENT", "TRIP_EARNING", "COMMISSION_FEE", "WITHDRAW", "REFUND", "TOP_UP", "RIDE_PAYMENT"))
            throw BadRequestException(message = "Loại giao dịch không hợp lệ")
        if (status != null && status !in setOf("PENDING", "SUCCESS", "FAILED"))
            throw BadRequestException(message = "Trạng thái giao dịch không hợp lệ")
    }
}
