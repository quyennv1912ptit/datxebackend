package com.nhom15.datxebackend.driver

import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.driver.dto.UpdateDriverAvatarRequest
import com.nhom15.datxebackend.driver.dto.UpdateDriverIdentityRequest
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.service.DriverProfileService
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Optional

class DriverBankProfileTests {
    @Test fun `identity and bank are saved together without touching verified license or dropping leading zeroes`() {
        val driver = Driver().apply { id = 7; firebaseUid = "driver-7"; licenseNumber = "B2-123"; licenseVerified = true }
        val repo = mock(DriverRepository::class.java)
        `when`(repo.findByIdForUpdate(7)).thenReturn(Optional.of(driver))
        val cache = mock(AuthenticatedUserCache::class.java)
        val service = DriverProfileService(repo, cache, mock(CloudinaryAssetService::class.java))
        TransactionSynchronizationManager.initSynchronization()
        try {
            val result = service.updateIdentity(7, UpdateDriverIdentityRequest(" Nguyễn Văn A ", "a@example.com", " Vietcombank ", " 00123456789 "))
            assertEquals("Vietcombank", result.bankName)
            assertEquals("00123456789", result.bankAccountNumber)
            assertEquals("00123456789", driver.bankAccountNumber)
            assertEquals("B2-123", driver.licenseNumber)
            assertTrue(driver.licenseVerified == true)
            verifyNoInteractions(cache)
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            verify(cache).evict("driver-7")
        } finally { TransactionSynchronizationManager.clearSynchronization() }
    }
    @Test fun `older clients preserve bank details and explicit blank fields clear them`() {
        val driver = Driver().apply { id = 7; bankName = "VCB"; bankAccountNumber = "00123" }
        val repo = mock(DriverRepository::class.java)
        `when`(repo.findByIdForUpdate(7)).thenReturn(Optional.of(driver))
        val service = DriverProfileService(repo, mock(AuthenticatedUserCache::class.java), mock(CloudinaryAssetService::class.java))
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.updateIdentity(7, UpdateDriverIdentityRequest("Nguyễn Văn A"))
            assertEquals("VCB", driver.bankName)
            assertEquals("00123", driver.bankAccountNumber)
            service.updateIdentity(7, UpdateDriverIdentityRequest("Nguyễn Văn A", bankName = " ", bankAccountNumber = " "))
            assertNull(driver.bankName)
            assertNull(driver.bankAccountNumber)
        } finally { TransactionSynchronizationManager.clearSynchronization() }
    }

    @Test fun `avatar update stores the owned Cloudinary asset and removes the previous one`() {
        val driver = Driver().apply {
            id = 7
            firebaseUid = "driver-7"
            avatarUrl = "https://res.cloudinary.com/demo/image/upload/v1/app_dat_xe/driver/7/old"
            avatarPublicId = "app_dat_xe/driver/7/old"
        }
        val repo = mock(DriverRepository::class.java)
        `when`(repo.findByIdForUpdate(7)).thenReturn(Optional.of(driver))
        `when`(repo.save(any(Driver::class.java))).thenAnswer { it.arguments[0] }
        val cloudinary = mock(CloudinaryAssetService::class.java)
        val service = DriverProfileService(repo, mock(AuthenticatedUserCache::class.java), cloudinary)
        val url = "https://res.cloudinary.com/demo/image/upload/v1/app_dat_xe/driver/7/new"
        val publicId = "app_dat_xe/driver/7/new"

        val result = service.updateAvatar(7, UpdateDriverAvatarRequest(url, publicId))

        assertEquals(url, result.avatarUrl)
        assertEquals(url, driver.avatarUrl)
        verify(cloudinary).validateOwnedImage(Role.DRIVER, 7L, url, publicId)
        verify(cloudinary).deleteAfterCommit("app_dat_xe/driver/7/old")
    }

    @Test fun `legacy avatar key outside the owner folder does not block an avatar change`() {
        val driver = Driver().apply {
            id = 7
            firebaseUid = "driver-7"
            avatarUrl = "https://res.cloudinary.com/demo/image/upload/v1/legacy/old-avatar"
            avatarPublicId = "legacy/old-avatar"
        }
        val repo = mock(DriverRepository::class.java)
        `when`(repo.findByIdForUpdate(7)).thenReturn(Optional.of(driver))
        `when`(repo.save(any(Driver::class.java))).thenAnswer { it.arguments[0] }
        val cloudinary = mock(CloudinaryAssetService::class.java)
        val service = DriverProfileService(repo, mock(AuthenticatedUserCache::class.java), cloudinary)
        val url = "https://res.cloudinary.com/demo/image/upload/v1/app_dat_xe/driver/7/new"
        val publicId = "app_dat_xe/driver/7/new"

        val result = service.updateAvatar(7, UpdateDriverAvatarRequest(url, publicId))

        assertEquals(publicId, driver.avatarPublicId)
        verify(cloudinary).validateOwnedImage(Role.DRIVER, 7L, url, publicId)
        verify(cloudinary).deleteAfterCommit("legacy/old-avatar")
    }
}
