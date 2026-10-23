package com.nhom15.datxebackend.customer.entity

import com.nhom15.datxebackend.common.entity.BaseEntity
import com.nhom15.datxebackend.common.entity.RoleAwareEntity
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.UserStatus
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Instant
import jakarta.persistence.Transient

@Entity
@Table(name = "customers")
class Customer : BaseEntity(), RoleAwareEntity {
    @Column(name = "avatar_url", columnDefinition = "text")
    var avatarUrl: String? = null

    @Column(name = "average_rating")
    var averageRating: Double? = null

    @Column(name = "default_payment_method")
    var defaultPaymentMethod: String? = null

    @Column(name = "email")
    var email: String? = null

    @Column(name = "firebase_uid", nullable = false, unique = true)
    var firebaseUid: String = ""

    @Column(name = "full_name")
    var fullName: String? = null

    @Column(name = "phone_number", unique = true)
    var phoneNumber: String? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: UserStatus = UserStatus.ACTIVE

    @Column(name = "wallet_balance")
    var walletBalance: Long? = 0

    @Column(name = "cancel_rate_30d")
    var cancelRate30d: Double? = 0.0

    @Column(name = "date_of_birth")
    var dateOfBirth: LocalDate? = null

    @Column(name = "gender")
    var gender: String? = null

    @Column(name = "is_flagged")
    var isFlagged: Boolean? = false

    @Column(name = "last_login_at")
    var lastLoginAt: LocalDateTime? = null

    @Column(name = "total_cancelled")
    var totalCancelled: Int? = 0

    @Column(name = "total_trips")
    var totalTrips: Int? = 0

    @Column(name = "avatar_public_id", columnDefinition = "text")
    var avatarPublicId: String? = null

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    @get:Transient
    override val role: Role
        get() = Role.CUSTOMER
}
