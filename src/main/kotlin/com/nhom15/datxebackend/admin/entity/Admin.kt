package com.nhom15.datxebackend.admin.entity

import com.nhom15.datxebackend.common.entity.BaseEntity
import com.nhom15.datxebackend.common.entity.RoleAwareEntity
import com.nhom15.datxebackend.common.enums.Role
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Transient

@Entity
@Table(name = "admins")
class Admin : BaseEntity(), RoleAwareEntity {
    @Column(name = "firebase_uid", nullable = false, unique = true)
    var firebaseUid: String = ""

    @Column(name = "email", nullable = false, unique = true)
    var email: String = ""

    @Column(name = "full_name", nullable = false)
    var fullName: String = ""

    @Column(name = "status", nullable = false, length = 20)
    var status: String = "ACTIVE"

    @get:Transient
    override val role: Role
        get() = Role.ADMIN
}
