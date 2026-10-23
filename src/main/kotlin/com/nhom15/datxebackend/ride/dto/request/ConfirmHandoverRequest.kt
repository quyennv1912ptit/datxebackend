package com.nhom15.datxebackend.ride.dto.request

import jakarta.validation.constraints.AssertTrue

data class ConfirmHandoverRequest(
    @field:AssertTrue(message = "Khách hàng phải xác nhận biên bản bàn giao")
    val confirmed: Boolean,
)
