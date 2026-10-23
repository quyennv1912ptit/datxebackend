package com.nhom15.datxebackend.common.domain

import jakarta.persistence.Embeddable

@Embeddable
data class GeoPoint(var lat: Double = 0.0, var lng: Double = 0.0) {
    init {
        require(lat in -90.0..90.0) { "lat không hợp lệ" }
        require(lng in -180.0..180.0) { "lng không hợp lệ" }
    }
}
