package com.nhom15.datxebackend.routing.model

import com.nhom15.datxebackend.common.domain.GeoPoint

/**
 * Một địa điểm do Goong trả về quanh ghim bản đồ. `distanceMeters` do backend
 * tự tính lại từ tọa độ để client không phải đoán khoảng cách.
 */
data class NearbyPlace(
    val id: String,
    val name: String,
    val address: String,
    val provider: String,
    val coordinate: GeoPoint,
    val distanceMeters: Int,
)

/**
 * `withinRadius = false` khi Goong chưa có điểm nào đúng bán kính yêu cầu;
 * khi đó `places` chứa các điểm gần nhất để người dùng vẫn có lựa chọn.
 */
data class NearbyPlacesResult(
    val provider: String,
    val radiusMeters: Int,
    val withinRadius: Boolean,
    val places: List<NearbyPlace>,
)
