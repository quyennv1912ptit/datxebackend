package com.nhom15.datxebackend.admin.repository

import com.nhom15.datxebackend.admin.entity.Admin
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface AdminRepository : JpaRepository<Admin, Long> {
    fun findByFirebaseUid(firebaseUid: String): Optional<Admin>
    fun findByEmailIgnoreCase(email: String): Optional<Admin>
    fun existsByFirebaseUid(firebaseUid: String): Boolean
}
