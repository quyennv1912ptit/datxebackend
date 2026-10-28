package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.pricing.model.RideQuote
import com.nhom15.datxebackend.ride.dto.response.CreateRideResponse
import com.nhom15.datxebackend.ride.dto.response.EstimateRideResponse
import com.nhom15.datxebackend.ride.dto.response.GeoPointResponse
import com.nhom15.datxebackend.ride.dto.response.HandoverReportResponse
import com.nhom15.datxebackend.ride.dto.response.RideDetailResponse
import com.nhom15.datxebackend.ride.dto.response.RideStatusHistoryResponse
import com.nhom15.datxebackend.ride.dto.response.RideStatusResponse
import com.nhom15.datxebackend.ride.dto.response.RideSummaryResponse
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideActorRole
import com.nhom15.datxebackend.ride.entity.RideStatusHistory

internal object RideResponseMapper {
    private fun savedRoute(ride: Ride): com.nhom15.datxebackend.routing.model.SavedRideRoute? {
        val polyline = ride.routePolyline ?: return null
        return com.nhom15.datxebackend.routing.model.SavedRideRoute("ride:${ride.id}", polyline, ride.routeDistanceMeters ?: return null, ride.routeDurationSeconds ?: return null, ride.routeProvider ?: "unknown", ride.vehicleType, ride.routeLimitation)
    }
    fun estimate(quote: RideQuote, discountAmount: Long = 0, route: com.nhom15.datxebackend.routing.model.SavedRideRoute? = null) = EstimateRideResponse(
        route = route,
        pricingRuleId = quote.pricingRuleId,
        distanceKm = quote.distanceKm,
        estimatedDurationMinutes = quote.estimatedDurationMinutes,
        baseFare = quote.baseFare,
        distanceFare = quote.distanceFare,
        waitingFare = quote.waitingFare,
        nightSurcharge = quote.nightSurcharge,
        returnFee = quote.returnFee,
        estimatedFare = quote.estimatedFare,
        discountAmount = discountAmount,
        payableFare = (quote.estimatedFare - discountAmount).coerceAtLeast(0),
    )

    fun create(ride: Ride) = CreateRideResponse(
        route = savedRoute(ride),
        id = ride.id, status = ride.status,
        estimatedFare = ride.fareEstimate,
        createdAt = ride.createdAt,
        discountAmount = ride.discountAmount,
        payableFare = (ride.fareEstimate - ride.discountAmount).coerceAtLeast(0),
    )

    fun status(ride: Ride, previousStatus: RideStatus) = RideStatusResponse(
        rideId = ride.id, previousStatus = previousStatus,
        currentStatus = ride.status, changedAt = ride.updatedAt,
    )

    fun summary(ride: Ride) = RideSummaryResponse(
        id = ride.id, pickupAddress = ride.pickup.address,
        dropoffAddress = ride.dropoff.address, type = ride.type, status = ride.status,
        estimatedFare = ride.fareEstimate, finalFare = ride.finalFare,
        scheduledAt = ride.scheduledAt, createdAt = ride.createdAt,
    )

    fun history(history: RideStatusHistory) = RideStatusHistoryResponse(
        id = history.id, fromStatus = history.fromStatus, toStatus = history.toStatus,
        actorId = history.actorId, note = history.note, createdAt = history.createdAt,
        actorRole = history.actorRole,
    )

    fun detail(
        ride: Ride,
        statusHistory: List<RideStatusHistoryResponse>,
        handoverReports: List<HandoverReportResponse>,
        unreadChatCount: Long = 0,
    ) = RideDetailResponse(
        route = savedRoute(ride),
        id = ride.id, customerId = ride.customerId,
        driverId = ride.driverId, vehicleId = ride.vehicleId, type = ride.type,
        scheduledAt = ride.scheduledAt, pickupAddress = ride.pickup.address,
        pickup = GeoPointResponse(ride.pickup.geoPoint.lat, ride.pickup.geoPoint.lng),
        dropoffAddress = ride.dropoff.address,
        dropoff = GeoPointResponse(ride.dropoff.geoPoint.lat, ride.dropoff.geoPoint.lng),
        distanceKm = ride.distanceKm?.toBigDecimal(), estimatedFare = ride.fareEstimate,
        finalFare = ride.finalFare, status = ride.status, cancelReason = ride.cancelReason,
        cancelledBy = ride.cancelledBy, statusHistory = statusHistory,
        handoverReports = handoverReports, createdAt = ride.createdAt, updatedAt = ride.updatedAt,
        acceptedAt = ride.acceptedAt, startedAt = ride.startedAt,
        completedAt = ride.completedAt, cancelledAt = ride.cancelledAt,
        cancelledByRole = ride.cancelledByRole?.let { RideActorRole.valueOf(it) }, discountAmount = ride.discountAmount,
        unreadChatCount = unreadChatCount,
        pickupNote = ride.pickupNote,
    )
}
