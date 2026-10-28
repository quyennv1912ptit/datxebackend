package com.nhom15.datxebackend.ride.entity

import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.entity.BaseEntity
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(
    name = "rides",
    indexes = [
        Index(name = "idx_rides_customer_created", columnList = "customer_id, created_at"),
        Index(name = "idx_rides_customer_status", columnList = "customer_id, status"),
        Index(name = "idx_rides_driver_status", columnList = "driver_id, status"),
        Index(name = "idx_rides_status_scheduled", columnList = "status, scheduled_at"),
        Index(name = "idx_rides_vehicle", columnList = "vehicle_id")
    ]
)
class Ride(
    @Column(name = "customer_id", nullable = false)
    val customerId: Long,

    @Column(name = "driver_id")
    var driverId: Long? = null,

    @Column(name = "vehicle_id", nullable = false)
    var vehicleId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: RideStatus = RideStatus.PENDING,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    val type: RideType,

    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "geoPoint.lat", column = Column(name = "pickup_lat")),
        AttributeOverride(name = "geoPoint.lng", column = Column(name = "pickup_lng")),
        AttributeOverride(name = "address", column = Column(name = "pickup_address"))
    )
    var pickup: Location,

    @Embedded
    @AttributeOverrides(
        AttributeOverride(name = "geoPoint.lat", column = Column(name = "dropoff_lat")),
        AttributeOverride(name = "geoPoint.lng", column = Column(name = "dropoff_lng")),
        AttributeOverride(name = "address", column = Column(name = "dropoff_address"))
    )
    var dropoff: Location,

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", nullable = false, length = 30)
    val vehicleType: VehicleType,

    @Enumerated(EnumType.STRING)
    @Column(name = "transmission", nullable = false, length = 30)
    val transmission: Transmission,

    @Column(name = "license_plate", length = 20)
    val licensePlate: String? = null,

    @Column(name = "fare_estimate", nullable = false)
    val fareEstimate: Long,

    @Column(name = "route_polyline", columnDefinition = "text")
    var routePolyline: String? = null,
    @Column(name = "route_distance_meters")
    var routeDistanceMeters: Double? = null,
    @Column(name = "route_duration_seconds")
    var routeDurationSeconds: Double? = null,
    @Column(name = "route_provider")
    var routeProvider: String? = null,
    @Column(name = "route_limitation", columnDefinition = "text")
    var routeLimitation: String? = null,

    @Column(name = "final_fare")
    var finalFare: Long? = null,

    @Column(name = "discount_amount")
    var discountAmount: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 30)
    val paymentMethod: PaymentMethod,

    @Column(name = "note", length = 500)
    val note: String? = null,

    @Column(name = "scheduled_at")
    val scheduledAt: Instant? = null,

    @Column(name = "distance_km")
    var distanceKm: Double? = null,

    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null,

    @Column(name = "started_at")
    var startedAt: Instant? = null,

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "cancelled_at")
    var cancelledAt: Instant? = null,

    @Column(name = "cancel_reason")
    var cancelReason: String? = null,

    @Column(name = "cancelled_by")
    var cancelledBy: Long? = null,

    @Column(name = "cancelled_by_role", length = 20)
    var cancelledByRole: String? = null

) : BaseEntity() {

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
        protected set
}
