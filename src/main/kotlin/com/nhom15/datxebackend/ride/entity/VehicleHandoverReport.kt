package com.nhom15.datxebackend.ride.entity

import com.nhom15.datxebackend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "vehicle_handover_reports",
    uniqueConstraints = [UniqueConstraint(name = "uk_handover_ride_phase", columnNames = ["ride_id", "phase"])],
)
class VehicleHandoverReport(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ride_id", nullable = false, foreignKey = ForeignKey(name = "fk_handover_reports_ride"))
    var ride: Ride,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var phase: HandoverPhase,

    @Column(nullable = false)
    var odometer: Long,

    @Column(name = "fuel_level")
    var fuelLevel: Int? = null,

    @Column(columnDefinition = "text")
    var notes: String? = null,

    @Column(name = "confirmed_by_customer", nullable = false)
    var confirmedByCustomer: Boolean = false,
) : BaseEntity()
