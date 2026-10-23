package com.nhom15.datxebackend.driver.entity

import com.nhom15.datxebackend.common.entity.BaseEntity
import com.nhom15.datxebackend.common.entity.RoleAwareEntity
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.VehicleType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Instant
import jakarta.persistence.Transient

@Entity
@Table(name = "drivers")
class Driver : BaseEntity(), RoleAwareEntity {
    @Column(name = "avatar_url", columnDefinition = "text")
    var avatarUrl: String? = null

    @Column(name = "average_rating")
    var averageRating: Double? = null

    @Column(name = "bank_account_number")
    var bankAccountNumber: String? = null

    @Column(name = "email")
    var email: String? = null

    @Column(name = "firebase_uid", nullable = false, unique = true)
    var firebaseUid: String = ""

    @Column(name = "full_name")
    var fullName: String? = null

    @Column(name = "last_location_update")
    var lastLocationUpdate: LocalDateTime? = null

    @Column(name = "license_class", length = 10)
    var licenseClass: String? = null

    @Column(name = "license_number")
    var licenseNumber: String? = null

    @Column(name = "income_balance")
    var incomeBalance: Long? = 0

    @Column(name = "phone_number", nullable = false, unique = true)
    var phoneNumber: String = ""

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: DriverStatus = DriverStatus.OFFLINE

    @Enumerated(EnumType.STRING)
    @Column(name = "online_vehicle_type", length = 30)
    var onlineVehicleType: VehicleType? = null

    @Column(name = "credit_balance")
    var creditBalance: Long? = 0

    @Column(name = "years_of_experience")
    var yearsOfExperience: Int? = null

    @Column(name = "acceptance_rate")
    var acceptanceRate: Double? = null

    @Column(name = "background_check_status")
    var backgroundCheckStatus: String? = null

    @Column(name = "cancel_rate_30d")
    var cancelRate30d: Double? = null

    @Column(name = "completion_rate")
    var completionRate: Double? = null

    @Column(name = "is_flagged")
    var isFlagged: Boolean? = false

    @Column(name = "license_expiry_date")
    var licenseExpiryDate: LocalDate? = null

    @Column(name = "license_verified")
    var licenseVerified: Boolean? = false

    @Column(name = "license_image_url", columnDefinition = "text")
    var licenseImageUrl: String? = null

    @Column(name = "total_trips")
    var totalTrips: Int? = 0

    @Column(name = "pending_payout")
    var pendingPayout: Long? = null

    @Column(name = "wallet_balance")
    var walletBalance: Long? = null

    @Column(name = "avatar_public_id", columnDefinition = "text")
    var avatarPublicId: String? = null

    @Column(name = "verification_status", length = 20)
    var verificationStatus: String? = "PENDING"

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    @get:Transient
    override val role: Role
        get() = Role.DRIVER
}
