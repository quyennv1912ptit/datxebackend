package com.nhom15.datxebackend.customer.repository

import com.nhom15.datxebackend.customer.entity.Customer
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface CustomerRepository : JpaRepository<Customer, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select customer from Customer customer where customer.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Optional<Customer>

    fun findByFirebaseUid(firebaseUid: String): Optional<Customer>
    fun existsByFirebaseUid(firebaseUid: String): Boolean
    fun findByPhoneNumber(phoneNumber: String): Optional<Customer>
}
