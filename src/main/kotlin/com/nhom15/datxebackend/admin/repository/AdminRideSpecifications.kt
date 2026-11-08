package com.nhom15.datxebackend.admin.repository

import com.nhom15.datxebackend.admin.dto.AdminSearchFilter
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.ride.dto.request.RideListFilter
import com.nhom15.datxebackend.ride.entity.Ride
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.time.Instant

object AdminRideSpecifications {
    fun matching(filter: RideListFilter, statuses: List<RideStatus>?): Specification<Ride> = Specification { ride, query, cb ->
        val predicates = mutableListOf<Predicate>()
        statuses?.let { predicates += ride.get<RideStatus>("status").`in`(it) }
        filter.createdFrom?.let { predicates += cb.greaterThanOrEqualTo(ride.get<Instant>("createdAt"), it) }
        filter.createdTo?.let { predicates += cb.lessThan(ride.get<Instant>("createdAt"), it) }
        filter.q?.trim()?.takeIf(String::isNotEmpty)?.let { term ->
            val pattern = AdminSearchFilter(q = term).pattern()
            val customers = query.subquery(Long::class.javaObjectType)
            val customer = customers.from(Customer::class.java)
            customers.select(customer.get("id")).where(cb.or(
                cb.like(cb.lower(customer.get("fullName")), pattern, '!'),
                cb.like(cb.lower(customer.get("phoneNumber")), pattern, '!'),
            ))
            val drivers = query.subquery(Long::class.javaObjectType)
            val driver = drivers.from(Driver::class.java)
            drivers.select(driver.get("id")).where(cb.or(
                cb.like(cb.lower(driver.get("fullName")), pattern, '!'),
                cb.like(cb.lower(driver.get("phoneNumber")), pattern, '!'),
            ))
            val matches = mutableListOf(ride.get<Long>("customerId").`in`(customers), ride.get<Long>("driverId").`in`(drivers))
            term.removePrefix("#").toLongOrNull()?.let { matches += cb.equal(ride.get<Long>("id"), it) }
            predicates += cb.or(*matches.toTypedArray())
        }
        cb.and(*predicates.toTypedArray())
    }
}
