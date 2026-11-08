package com.nhom15.datxebackend.admin.repository

import com.nhom15.datxebackend.admin.dto.AdminSearchFilter
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.payment.entity.WithdrawalRequest
import com.nhom15.datxebackend.payment.entity.WithdrawalStatus
import jakarta.persistence.criteria.Predicate
import org.springframework.data.jpa.domain.Specification
import java.time.Instant

object AdminWithdrawalSpecifications {
    fun matching(status: WithdrawalStatus?, filter: AdminSearchFilter): Specification<WithdrawalRequest> = Specification { request, query, cb ->
        val predicates = mutableListOf<Predicate>()
        status?.let { predicates += cb.equal(request.get<WithdrawalStatus>("status"), it) }
        filter.createdFrom?.let { predicates += cb.greaterThanOrEqualTo(request.get<Instant>("createdAt"), it) }
        filter.createdTo?.let { predicates += cb.lessThan(request.get<Instant>("createdAt"), it) }
        filter.q?.trim()?.takeIf(String::isNotEmpty)?.let { term ->
            val drivers = query.subquery(Long::class.javaObjectType)
            val driver = drivers.from(Driver::class.java)
            val pattern = filter.pattern()
            drivers.select(driver.get("id")).where(cb.or(*listOf("fullName", "phoneNumber", "bankName", "bankAccountNumber")
                .map { cb.like(cb.lower(driver.get(it)), pattern, '!') }.toTypedArray()))
            val matches = mutableListOf<Predicate>(request.get<Long>("driverId").`in`(drivers))
            term.removePrefix("#").toLongOrNull()?.let { matches += cb.equal(request.get<Long>("id"), it) }
            predicates += cb.or(*matches.toTypedArray())
        }
        cb.and(*predicates.toTypedArray())
    }
}
