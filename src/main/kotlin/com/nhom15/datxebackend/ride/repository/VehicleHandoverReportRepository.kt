package com.nhom15.datxebackend.ride.repository

import com.nhom15.datxebackend.ride.entity.HandoverPhase
import com.nhom15.datxebackend.ride.entity.VehicleHandoverReport
import org.springframework.data.jpa.repository.JpaRepository

interface VehicleHandoverReportRepository : JpaRepository<VehicleHandoverReport, Long> {
    fun findByRideIdAndPhase(rideId: Long, phase: HandoverPhase): VehicleHandoverReport?

    fun existsByRideIdAndPhase(rideId: Long, phase: HandoverPhase): Boolean
}
