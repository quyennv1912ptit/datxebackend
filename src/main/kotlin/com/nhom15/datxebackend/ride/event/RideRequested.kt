package com.nhom15.datxebackend.ride.event

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.event.DomainEvent
import java.time.Instant
import java.util.UUID

/** Phát bởi booking-core khi khách tạo booking xong (status = SEARCHING). Matching nghe để tìm tài xế. */
data class RideRequested(
    val rideId: Long,
    val customerId: Long,
    val pickup: GeoPoint,
    val vehicleType: VehicleType,          // thêm: ô tô/xe máy, quyết định tài xế nào phù hợp
    val transmission: Transmission,
    val scheduledAt: Instant? = null,      // thêm: null = đặt ngay, có giá trị = đặt trước
    override val eventId: UUID = UUID.randomUUID(),
    override val occurredAt: Instant = Instant.now()
) : DomainEvent
