package com.nhom15.datxebackend.common.entity

import com.nhom15.datxebackend.common.enums.Role
import jakarta.persistence.Transient

interface RoleAwareEntity {
    @get:Transient
    val role: Role
}
