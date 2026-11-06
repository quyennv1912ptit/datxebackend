package com.nhom15.datxebackend.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Circular service area used at ride creation. Kept configuration-driven so the
 * operational area can change without a code deployment.
 */
@ConfigurationProperties("app.service-area")
data class ServiceAreaProperties(
    val enabled: Boolean = true,
    val centerLat: Double = 21.0285,
    val centerLng: Double = 105.8542,
    val radiusKm: Double = 50.0,
) {
    init {
        require(centerLat.isFinite() && centerLat in -85.05112878..85.05112878) {
            "app.service-area.center-lat không hợp lệ"
        }
        require(centerLng.isFinite() && centerLng in -180.0..180.0) {
            "app.service-area.center-lng không hợp lệ"
        }
        require(radiusKm.isFinite() && radiusKm > 0.0) {
            "app.service-area.radius-km phải lớn hơn 0"
        }
    }
}
