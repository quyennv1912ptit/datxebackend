package com.nhom15.datxebackend.pricing.api

import com.nhom15.datxebackend.pricing.model.RideQuote
import com.nhom15.datxebackend.pricing.model.RideQuoteInput

/** Tính giá theo tọa độ và thời điểm, dùng chung cho tạo ride và báo giá. */
fun interface RideQuoteProvider {
    fun requirePricing(vehicleType: com.nhom15.datxebackend.common.enums.VehicleType) {}
    fun quote(input: RideQuoteInput): RideQuote
}
