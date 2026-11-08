package com.nhom15.datxebackend.driver

import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.driver.dto.UpdateDriverIdentityRequest
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.service.DriverProfileService
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
        val service = DriverProfileService(repo, cache)
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
        val service = DriverProfileService(repo, mock(AuthenticatedUserCache::class.java))
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
}
