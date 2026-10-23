package com.nhom15.datxebackend.ride.repository

import com.nhom15.datxebackend.ride.entity.RideStatusHistory
import org.springframework.data.jpa.repository.JpaRepository

interface RideStatusHistoryRepository : JpaRepository<RideStatusHistory, Long> {
    fun findAllByRideIdOrderByCreatedAtAsc(rideId: Long): List<RideStatusHistory>

    fun findAllByRideIdOrderByCreatedAtAscIdAsc(rideId: Long): List<RideStatusHistory>
}
