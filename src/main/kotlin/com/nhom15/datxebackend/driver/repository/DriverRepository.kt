package com.nhom15.datxebackend.driver.repository

import com.nhom15.datxebackend.driver.entity.Driver
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface DriverRepository : JpaRepository<Driver, Long> {
    fun findByFirebaseUid(firebaseUid: String): Optional<Driver>
    fun existsByFirebaseUid(firebaseUid: String): Boolean
    fun findByPhoneNumber(phoneNumber: String): Optional<Driver>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select driver from Driver driver where driver.id = :driverId")
    fun findByIdForUpdate(@Param("driverId") driverId: Long): Optional<Driver>
}
