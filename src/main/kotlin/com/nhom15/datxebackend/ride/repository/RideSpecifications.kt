package com.nhom15.datxebackend.ride.repository

import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.ride.dto.request.RideListFilter
import com.nhom15.datxebackend.ride.entity.Ride
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.time.Instant
import java.util.Locale

object RideSpecifications {
    fun forUser(
        actorId: Long,
        role: Role,
        filter: RideListFilter,
        statuses: List<RideStatus>?,
    ): Specification<Ride> = Specification { ride, _, cb ->
        val predicates = mutableListOf<Predicate>()
        predicates += when (role) {
            Role.CUSTOMER -> cb.equal(ride.get<Long>("customerId"), actorId)
            Role.DRIVER -> cb.equal(ride.get<Long>("driverId"), actorId)
            Role.ADMIN -> cb.disjunction()
        }
        statuses?.let { predicates += ride.get<RideStatus>("status").`in`(it) }
        filter.createdFrom?.let { predicates += cb.greaterThanOrEqualTo(ride.get<Instant>("createdAt"), it) }
        filter.createdTo?.let { predicates += cb.lessThan(ride.get<Instant>("createdAt"), it) }
        filter.q?.trim()?.takeIf(String::isNotEmpty)?.let { term ->
            val pattern = "%" + term.lowercase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%"
            val pickupAddress = cb.coalesce(ride.get<Location>("pickup").get<String>("address"), "")
            val dropoffAddress = cb.coalesce(ride.get<Location>("dropoff").get<String>("address"), "")
            val licensePlate = cb.coalesce(ride.get<String>("licensePlate"), "")
            val matches = mutableListOf<Predicate>(
                cb.like(cb.lower(pickupAddress), pattern, '!'),
                cb.like(cb.lower(dropoffAddress), pattern, '!'),
                cb.like(cb.lower(licensePlate), pattern, '!'),
            )
            term.removePrefix("#").toLongOrNull()?.takeIf { it > 0 }?.let { rideId ->
                matches += cb.equal(ride.get<Long>("id"), rideId)
            }
            predicates += cb.or(*matches.toTypedArray())
        }
        cb.and(*predicates.toTypedArray())
    }
}
