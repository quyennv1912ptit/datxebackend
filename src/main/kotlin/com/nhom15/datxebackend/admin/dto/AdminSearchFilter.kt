package com.nhom15.datxebackend.admin.dto

import com.nhom15.datxebackend.common.exception.BadRequestException
import org.springframework.format.annotation.DateTimeFormat
import java.time.Instant

/** UTC half-open interval [createdFrom, createdTo), converted from local dates by the client. */
data class AdminSearchFilter(
    val q: String? = null,
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) val createdFrom: Instant? = null,
    @param:DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) val createdTo: Instant? = null,
) {
    fun validate() {
        if ((q?.length ?: 0) > 200) throw BadRequestException(message = "Từ khóa tối đa 200 ký tự")
        if (createdFrom != null && createdTo != null && createdFrom >= createdTo)
            throw BadRequestException(message = "Khoảng ngày không hợp lệ")
    }
    fun pattern(): String = "%" + q.orEmpty().trim().lowercase(java.util.Locale.ROOT)
        .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
}
