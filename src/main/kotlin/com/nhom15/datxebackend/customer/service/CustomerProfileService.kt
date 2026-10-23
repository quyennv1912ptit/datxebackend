package com.nhom15.datxebackend.customer.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.customer.dto.CustomerProfileResponse
import com.nhom15.datxebackend.customer.dto.UpdateCustomerAvatarRequest
import com.nhom15.datxebackend.customer.dto.UpdateCustomerProfileRequest
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CustomerProfileService(
    private val customerRepository: CustomerRepository,
    private val cloudinaryAssetService: CloudinaryAssetService,
) {
    @Transactional(readOnly = true)
    fun get(actor: AuthUser): CustomerProfileResponse {
        requireCustomer(actor)
        return response(getCustomer(actor.id))
    }

    @Transactional
    fun update(actor: AuthUser, request: UpdateCustomerProfileRequest): CustomerProfileResponse {
        requireCustomer(actor)
        val customer = getCustomer(actor.id)
        customer.fullName = request.fullName.trim()
        customer.email = request.email?.trim()?.takeIf(String::isNotEmpty)
        customer.dateOfBirth = request.dateOfBirth
        customer.gender = request.gender?.trim()?.takeIf(String::isNotEmpty)?.uppercase()
        customer.defaultPaymentMethod = request.defaultPaymentMethod?.name
        return response(customerRepository.save(customer))
    }

    @Transactional
    fun updateAvatar(actor: AuthUser, request: UpdateCustomerAvatarRequest): CustomerProfileResponse {
        requireCustomer(actor)
        cloudinaryAssetService.validateCustomerImage(actor, request.secureUrl, request.publicId)
        val customer = getCustomer(actor.id)
        val oldPublicId = customer.avatarPublicId
        customer.avatarUrl = request.secureUrl.trim()
        customer.avatarPublicId = request.publicId.trim()
        val saved = customerRepository.save(customer)
        if (oldPublicId != saved.avatarPublicId) cloudinaryAssetService.deleteAfterCommit(oldPublicId)
        return response(saved)
    }

    private fun getCustomer(customerId: Long): Customer = customerRepository.findById(customerId)
        .orElseThrow { NotFoundException(ErrorCode.USER_NOT_FOUND) }

    private fun response(customer: Customer): CustomerProfileResponse = CustomerProfileResponse(
        id = requireNotNull(customer.id),
        fullName = customer.fullName,
        email = customer.email,
        phoneNumber = customer.phoneNumber,
        dateOfBirth = customer.dateOfBirth,
        gender = customer.gender,
        defaultPaymentMethod = customer.defaultPaymentMethod?.let {
            runCatching { PaymentMethod.valueOf(it) }.getOrNull()
        },
        avatarUrl = customer.avatarUrl,
    )

    private fun requireCustomer(actor: AuthUser) {
        if (actor.role != Role.CUSTOMER) throw ForbiddenException(message = "Chỉ khách hàng được quản lý hồ sơ")
    }
}
