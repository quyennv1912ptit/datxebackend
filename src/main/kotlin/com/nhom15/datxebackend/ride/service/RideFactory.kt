package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.port.CustomerVehicleSnapshot
import com.nhom15.datxebackend.pricing.model.RideQuote
import com.nhom15.datxebackend.ride.dto.request.CreateRideRequest
import com.nhom15.datxebackend.ride.entity.Ride

internal object RideFactory {
    fun create(
        request: CreateRideRequest,
        customerId: Long,
        vehicle: CustomerVehicleSnapshot,
        quote: RideQuote,
        discountAmount: Long,
    ) = Ride(
        customerId = customerId,
        vehicleId = vehicle.id,
        vehicleType = vehicle.vehicleType,
        transmission = vehicle.transmission,
        licensePlate = vehicle.licensePlate,
        paymentMethod = request.paymentMethod,
        note = request.note?.trim(),
        type = request.type,
        scheduledAt = request.scheduledAt,
        pickup = Location(GeoPoint(request.pickupLat, request.pickupLng), request.pickupAddress),
        dropoff = Location(GeoPoint(request.dropoffLat, request.dropoffLng), request.dropoffAddress),
        distanceKm = quote.distanceKm.toDouble(),
        fareEstimate = quote.estimatedFare,
    ).also { it.discountAmount = discountAmount }
}
