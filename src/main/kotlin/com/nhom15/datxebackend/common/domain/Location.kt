package com.nhom15.datxebackend.common.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.Embedded

@Embeddable
class Location(
    @Embedded var geoPoint: GeoPoint = GeoPoint(),
    @Column(name = "address") var address: String = "",
) {
    fun toGeoPoint() = geoPoint
}
