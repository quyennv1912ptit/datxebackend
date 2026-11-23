package com.nhom15.datxebackend.auth

import com.google.firebase.auth.FirebaseAuth
import com.nhom15.datxebackend.auth.service.AccountCleanupOutboxRepository
import com.nhom15.datxebackend.auth.service.AccountCleanupOutboxWorker
import com.nhom15.datxebackend.auth.service.AccountCleanupTask
import com.nhom15.datxebackend.driver.verification.DriverVerificationStorageService
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.springframework.data.redis.core.StringRedisTemplate

/** An eKYC capture file must never be marked cleaned while it still exists on disk. */
class AccountCleanupOutboxWorkerTests {
    private data class Call(val method: String, val args: List<Any?>)

    private val calls = mutableListOf<Call>()
    private val task = AccountCleanupTask(
        id = 9L,
        firebaseUid = "uid-9",
        ownerRole = "DRIVER",
        ownerId = 42L,
        cloudinaryPublicIds = emptyList(),
        verificationStorageKeys = listOf("capture-9"),
    )

    private val outbox: AccountCleanupOutboxRepository = Mockito.mock(
        AccountCleanupOutboxRepository::class.java,
        Answer { invocation: InvocationOnMock ->
            calls += Call(invocation.method.name, invocation.arguments.toList())
            if (invocation.method.name == "claimDue") {
                listOf(task)
            } else {
                Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        },
    )
    private val firebase: FirebaseAuth = Mockito.mock(FirebaseAuth::class.java)
    private val redis: StringRedisTemplate = Mockito.mock(
        StringRedisTemplate::class.java,
        Mockito.withSettings().defaultAnswer(Answers.RETURNS_DEEP_STUBS),
    )
    private val cloudinary: CloudinaryAssetService = Mockito.mock(CloudinaryAssetService::class.java)

    private fun worker(storage: DriverVerificationStorageService) =
        AccountCleanupOutboxWorker(outbox, firebase, redis, cloudinary, storage)

    @Test
    fun `failed eKYC file deletion keeps the cleanup task retryable`() {
        val storage = Mockito.mock(DriverVerificationStorageService::class.java) { invocation ->
            if (invocation.method.name == "deleteOrThrow") throw IllegalStateException("disk busy")
            null
        }
        calls.clear()

        worker(storage).processPendingCleanup()

        assertTrue(calls.none { it.method == "markCompleted" })
        val retry = calls.single { it.method == "retry" }
        assertEquals(9L, retry.args[0])
        assertTrue((retry.args[1] as String).contains("disk busy"))
    }

    @Test
    fun `successful eKYC file deletion completes the cleanup task`() {
        val storage = Mockito.mock(DriverVerificationStorageService::class.java)
        calls.clear()

        worker(storage).processPendingCleanup()

        assertTrue(calls.none { it.method == "retry" })
        assertEquals(listOf(9L), calls.filter { it.method == "markCompleted" }.map { it.args.single() })
    }
}
