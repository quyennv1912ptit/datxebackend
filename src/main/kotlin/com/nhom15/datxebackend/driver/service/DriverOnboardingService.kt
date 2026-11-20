package com.nhom15.datxebackend.driver.service

import com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.driver.dto.DriverVerificationSummary
import com.nhom15.datxebackend.driver.repository.DriverRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DriverOnboardingService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val driverRepository: DriverRepository,
    private val verificationEligibility: DriverVerificationEligibility,
) {
    @Transactional(readOnly = true)
    fun verification(actor: AuthUser): DriverVerificationSummary {
        requireDriver(actor)
        val driver = driverRepository.findById(actor.id)
            .orElseThrow { NotFoundException(ErrorCode.USER_NOT_FOUND, "Không tìm thấy tài xế") }
        return DriverVerificationSummary(verificationEligibility.displayedStatus(driver))
    }

    private fun requireDriver(actor: AuthUser) {
        if (actor.role != Role.DRIVER) throw ForbiddenException(message = "Chỉ tài xế được quản lý hồ sơ tài xế")
    }
}
