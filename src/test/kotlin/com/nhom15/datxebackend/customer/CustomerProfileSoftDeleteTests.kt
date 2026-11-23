package com.nhom15.datxebackend.customer

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.UserStatus
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.customer.dto.UpdateCustomerAvatarRequest
import com.nhom15.datxebackend.customer.dto.UpdateCustomerProfileRequest
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.customer.service.CustomerProfileService
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional

class CustomerProfileSoftDeleteTests {
    private val actor = AuthUser(7L, "customer-uid", Role.CUSTOMER, null, null, null, "ACTIVE")
    private val repository = mock(CustomerRepository::class.java)
    private val cloudinary = mock(CloudinaryAssetService::class.java)
    private val service = CustomerProfileService(repository, cloudinary)

    @Test
    fun `soft deleted customer cannot change profile or avatar`() {
        val customer = Customer().apply {
            id = 7L
            deletedAt = Instant.now()
            status = UserStatus.INACTIVE
        }
        `when`(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(customer))

        val profileError = assertThrows(AppException::class.java) {
            service.update(actor, UpdateCustomerProfileRequest("Nguyễn Văn A"))
        }
        val avatarError = assertThrows(AppException::class.java) {
            service.updateAvatar(actor, UpdateCustomerAvatarRequest("https://example.com/a.jpg", "app_dat_xe/customer/7/new"))
        }

        assertEquals(ErrorCode.ACCOUNT_LOCKED, profileError.errorCode)
        assertEquals(ErrorCode.ACCOUNT_LOCKED, avatarError.errorCode)
        verify(repository, never()).save(any(Customer::class.java))
        verify(cloudinary, never()).deleteAfterCommit(anyString(), anyString())
    }

    @Test
    fun `banned customer is also locked out of profile writes`() {
        val customer = Customer().apply {
            id = 7L
            status = UserStatus.BANNED
        }
        `when`(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(customer))

        assertEquals(
            ErrorCode.ACCOUNT_LOCKED,
            assertThrows(AppException::class.java) {
                service.update(actor, UpdateCustomerProfileRequest("Nguyễn Văn A"))
            }.errorCode,
        )
    }

    @Test
    fun `avatar replacement passes the owner folder to the deletion guard`() {
        val customer = Customer().apply {
            id = 7L
            status = UserStatus.ACTIVE
            avatarPublicId = "app_dat_xe/customer/7/old"
        }
        `when`(repository.findByIdForUpdate(7L)).thenReturn(Optional.of(customer))
        `when`(repository.save(any(Customer::class.java))).thenAnswer { it.arguments[0] }

        service.updateAvatar(actor, UpdateCustomerAvatarRequest("https://example.com/a.jpg", "app_dat_xe/customer/7/new"))

        verify(cloudinary).deleteAfterCommit("app_dat_xe/customer/7/old", "app_dat_xe/customer/7")
    }
}
