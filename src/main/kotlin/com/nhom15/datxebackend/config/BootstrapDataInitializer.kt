package com.nhom15.datxebackend.config

import com.nhom15.datxebackend.admin.entity.Admin
import com.nhom15.datxebackend.admin.repository.AdminRepository
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.pricing.entity.PricingRule
import com.nhom15.datxebackend.pricing.repository.PricingRuleRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class BootstrapDataInitializer(
    private val adminRepository: AdminRepository,
    private val customerRepository: CustomerRepository,
    private val driverRepository: DriverRepository,
    private val pricingRuleRepository: PricingRuleRepository,
    @Value("\${app.bootstrap.admin.firebase-uid:}") private val adminFirebaseUid: String,
    @Value("\${app.bootstrap.admin.email:}") private val adminEmail: String,
    @Value("\${app.bootstrap.admin.full-name:}") private val adminFullName: String,
    @Value("\${app.bootstrap.pricing.enabled:false}") private val pricingEnabled: Boolean,
    @Value("\${app.bootstrap.pricing.car.base-fare:}") private val carBaseFare: String,
    @Value("\${app.bootstrap.pricing.car.per-km:}") private val carPerKm: String,
    @Value("\${app.bootstrap.pricing.car.min-fare:}") private val carMinFare: String,
    @Value("\${app.bootstrap.pricing.motorcycle.base-fare:}") private val motorcycleBaseFare: String,
    @Value("\${app.bootstrap.pricing.motorcycle.per-km:}") private val motorcyclePerKm: String,
    @Value("\${app.bootstrap.pricing.motorcycle.min-fare:}") private val motorcycleMinFare: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        seedAdmin()
        if (pricingEnabled) {
            seedPrice(VehicleType.CAR, carBaseFare, carPerKm, carMinFare)
            seedPrice(VehicleType.MOTORCYCLE, motorcycleBaseFare, motorcyclePerKm, motorcycleMinFare)
        }
    }

    private fun seedAdmin() {
        val uid = adminFirebaseUid.trim()
        val email = adminEmail.trim()
        val name = adminFullName.trim()
        if (uid.isEmpty() && email.isEmpty() && name.isEmpty()) return
        require(uid.isNotEmpty() && email.isNotEmpty() && name.isNotEmpty()) {
            "Cần cấu hình đầy đủ BOOTSTRAP_ADMIN_FIREBASE_UID, BOOTSTRAP_ADMIN_EMAIL và BOOTSTRAP_ADMIN_FULL_NAME"
        }
        if (adminRepository.existsByFirebaseUid(uid)) return
        require(!customerRepository.existsByFirebaseUid(uid) && !driverRepository.existsByFirebaseUid(uid)) {
            "BOOTSTRAP_ADMIN_FIREBASE_UID đã được liên kết với tài khoản customer hoặc driver"
        }
        require(adminRepository.findByEmailIgnoreCase(email).orElse(null) == null) {
            "Email bootstrap admin đã thuộc về Firebase UID khác"
        }
        adminRepository.saveAndFlush(
            Admin().apply {
                firebaseUid = uid
                this.email = email
                fullName = name
            },
        )
        log.info("Đã tạo tài khoản admin bootstrap từ Firebase UID cấu hình")
    }

    private fun seedPrice(type: VehicleType, baseFare: String, perKm: String, minFare: String) {
        if (pricingRuleRepository.findFirstByVehicleTypeAndActiveTrueOrderByCreatedAtDesc(type) != null) return
        val base = baseFare.toLongOrNull()
        val kilometer = perKm.toLongOrNull()
        val minimum = minFare.toLongOrNull()
        require(base != null && kilometer != null && minimum != null && base >= 0 && kilometer >= 0 && minimum >= 0) {
            "Cần cấu hình BOOTSTRAP_PRICING_${type.name}_BASE_FARE, _PER_KM và _MIN_FARE bằng số nguyên VND không âm"
        }
        pricingRuleRepository.saveAndFlush(
            PricingRule(
                baseFare = base,
                perKm = kilometer,
                perMinuteWaiting = 0,
                returnFeeForDriver = 0,
                minFare = minimum,
                active = true,
                vehicleType = type,
            ),
        )
        log.info("Đã tạo bảng giá bootstrap cho {}", type)
    }
}
