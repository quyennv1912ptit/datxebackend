package com.nhom15.datxebackend.auth

import com.google.firebase.auth.FirebaseAuth
import com.nhom15.datxebackend.auth.service.AccountCleanupOutboxRepository
import com.nhom15.datxebackend.auth.service.AccountCleanupOutboxWorker
import com.nhom15.datxebackend.auth.service.AccountCleanupTask
import com.nhom15.datxebackend.driver.verification.DriverVerificationStorageService
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate

class AccountCleanupOutboxPayloadTests {
    @Test
    fun `local capture keys are namespaced before sharing the outbox array column`() {
        assertEquals(
            "verification-storage:cap-123",
            AccountCleanupOutboxRepository.encodeVerificationStorageKey("cap-123"),
        )
    }

    @Test
    fun `worker removes cloudinary assets and encrypted eKYC files then completes the task`() {
        val outbox = mock(AccountCleanupOutboxRepository::class.java)
        val firebaseAuth = mock(FirebaseAuth::class.java)
        val redis = mock(StringRedisTemplate::class.java)
        val cloudinary = mock(CloudinaryAssetService::class.java)
        val storage = mock(DriverVerificationStorageService::class.java)
        val task = AccountCleanupTask(
            id = 9L,
            firebaseUid = "customer-uid",
            ownerRole = "CUSTOMER",
            ownerId = 3L,
            cloudinaryPublicIds = listOf("app_dat_xe/customer/3/avatar"),
            verificationStorageKeys = listOf("cap-1", "cap-2"),
        )
        `when`(outbox.claimDue()).thenReturn(listOf(task))

        AccountCleanupOutboxWorker(outbox, firebaseAuth, redis, cloudinary, storage).processPendingCleanup()

        verify(firebaseAuth).deleteUser("customer-uid")
        verify(cloudinary).deleteNowOrThrow("app_dat_xe/customer/3/avatar")
        // Deletion must be successful before the task can be marked completed.
        verify(storage).deleteOrThrow("cap-1")
        verify(storage).deleteOrThrow("cap-2")
        verify(outbox).markCompleted(9L)
        verify(outbox, never()).retry(anyLong(), anyString())
    }
}
