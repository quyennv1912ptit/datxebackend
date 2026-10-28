package com.nhom15.datxebackend.notification.dto

import com.fasterxml.jackson.annotation.JsonIgnore
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.websocket.PayloadType

data class LocationDto(
    val lat: Double,
    val lng: Double,
    val address: String
)

fun Location.toDto() = LocationDto(
    lat = geoPoint.lat,
    lng = geoPoint.lng,
    address = address
)

data class VehicleInfo(
    val type: VehicleType,
    val transmission: Transmission
)

data class CustomerBrief(
    val displayName: String,
    val rating: Double
)

data class RideOfferPayload(
    override val rideId: Long,
    val expiresAt: Long,

    val pickup: LocationDto,
    val dropoff: LocationDto,

    val distanceToPickupKm: Double,
    val etaToPickupMin: Int,

    val tripDistanceKm: Double,
    val estDurationMin: Int,

    val fareEstimate: Long,
    val driverEarning: Long,

    val paymentMethod: PaymentMethod,

    val vehicle: VehicleInfo,
    val customer: CustomerBrief,
    val note: String?,
    val pickupNote: String? = null
) : NotificationPayload {
    override val type: PayloadType get() = PayloadType.RIDE_OFFER
}

data class OfferClosedPayload(
    override val rideId: Long,
    @get:JsonIgnore
    override val type: PayloadType
) : NotificationPayload {

    init {
        require(type == PayloadType.OFFER_EXPIRED || type == PayloadType.OFFER_CANCELLED) {
            "OfferClosedPayload chỉ dùng cho OFFER_EXPIRED hoặc OFFER_CANCELLED, nhận được $type"
        }
    }

    companion object {
        /** Hết 15 giây mà tài xế không phản hồi. */
        fun expired(rideId: Long) = OfferClosedPayload(rideId, PayloadType.OFFER_EXPIRED)

        /** Khách huỷ khi lời mời đang treo. */
        fun cancelled(rideId: Long) = OfferClosedPayload(rideId, PayloadType.OFFER_CANCELLED)
    }
}
