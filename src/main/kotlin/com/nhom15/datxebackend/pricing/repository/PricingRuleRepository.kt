package com.nhom15.datxebackend.pricing.repository

import com.nhom15.datxebackend.pricing.entity.PricingRule
import com.nhom15.datxebackend.common.enums.VehicleType
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PricingRuleRepository : JpaRepository<PricingRule, Long> {
    fun findFirstByVehicleTypeAndActiveTrueOrderByCreatedAtDesc(vehicleType: VehicleType): PricingRule?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rule from PricingRule rule where rule.vehicleType = :vehicleType order by rule.id")
    fun findAllByVehicleTypeForUpdate(@Param("vehicleType") vehicleType: VehicleType): List<PricingRule>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rule from PricingRule rule where rule.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): PricingRule?

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PricingRule rule set rule.active = false where rule.vehicleType = :vehicleType and rule.active = true")
    fun deactivateByVehicleType(@Param("vehicleType") vehicleType: VehicleType): Int
}
