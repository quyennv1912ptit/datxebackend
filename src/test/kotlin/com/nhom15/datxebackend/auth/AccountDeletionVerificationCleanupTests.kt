package com.nhom15.datxebackend.auth

import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.auth.service.AccountCleanupOutboxRepository
import com.nhom15.datxebackend.auth.service.AccountDeletionService
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.notification.token.DeviceTokenRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Optional

/**
 * Mockito matchers (eq/any/capture) evaluate to null at the Kotlin call site and trip the
 * compiler's non-null checks on Kotlin interfaces, leaving a leaked matcher stack. Every mock
 * here records invocations instead, so assertions use plain concrete values.
 */
class AccountDeletionVerificationCleanupTests {
    private data class Call(val mock: String, val method: String, val args: List<Any?>)

    private val calls = mutableListOf<Call>()

    private val customerRepository = recordingMock(CustomerRepository::class.java, "customerRepository")
    private val driverRepository = recordingMock(DriverRepository::class.java, "driverRepository")
    private val deviceTokenRepository = recordingMock(DeviceTokenRepository::class.java, "deviceTokenRepository")
    private val rideRepository = recordingMock(RideRepository::class.java, "rideRepository")
    private val jdbcTemplate = recordingMock(
        JdbcTemplate::class.java,
        "jdbcTemplate",
        Answer { invocation ->
            if (invocation.method.name == "query") listOf("capture-active", "capture-retired")
            else Answers.RETURNS_DEFAULTS.answer(invocation)
        },
    )
    private val outbox = recordingMock(AccountCleanupOutboxRepository::class.java, "outbox")
    private val cache = recordingMock(AuthenticatedUserCache::class.java, "cache")

    private val actor = AuthUser(42L, "driver-uid-42", Role.DRIVER, null, null, null, "ONLINE")
    private val driver = Driver().apply {
        id = 42L
        firebaseUid = "driver-uid-42"
        phoneNumber = "0909123456"
        fullName = "Nguyễn Văn A"
        avatarPublicId = "app_dat_xe/driver/42/avatar"
    }

    private fun <T : Any> recordingMock(
        type: Class<T>,
        name: String,
        answer: Answer<Any?> = Answer { invocation -> Answers.RETURNS_DEFAULTS.answer(invocation) },
    ): T = mock(type, Answer { invocation ->
        calls.add(Call(name, invocation.method.name, invocation.flatArguments()))
        answer.answer(invocation)
    })

    private fun InvocationOnMock.flatArguments(): List<Any?> = arguments.flatMap { arg ->
        if (arg is Array<*>) arg.toList() else listOf(arg)
    }

    private fun callsFor(mockName: String, method: String): List<Call> =
        calls.filter { it.mock == mockName && it.method == method }

    private fun service() = AccountDeletionService(
        customerRepository,
        driverRepository,
        deviceTokenRepository,
        rideRepository,
        jdbcTemplate,
        outbox,
        cache,
    )

    @Test
    fun `driver deletion purges eKYC rows by owner uid and enqueues every capture file`() {
        TransactionSynchronizationManager.initSynchronization()
        try {
            `when`(driverRepository.findById(42L)).thenReturn(Optional.of(driver))
            calls.clear()

            service().deleteAccount(actor)

            // Keys are read BEFORE the owner_uid is tombstoned and before cascade delete.
            val queryCall = callsFor("jdbcTemplate", "query").single()
            assertTrue((queryCall.args[0] as String).contains("driver_verification_captures"))
            assertEquals("driver-uid-42", queryCall.args[2])

            // V38 cascades captures/submissions/submit_keys/audit from the application row.
            val verificationDeletes = callsFor("jdbcTemplate", "update")
                .filter { (it.args[0] as? String)?.startsWith("DELETE FROM driver_verification") == true }
                .map { (it.args[0] as String) to it.args[1] }
            assertEquals(
                listOf(
                    "DELETE FROM driver_verification_applications WHERE owner_uid = ?" to "driver-uid-42",
                    "DELETE FROM driver_verification_upload_attempts WHERE owner_uid = ?" to "driver-uid-42",
                    "DELETE FROM driver_verification_projection WHERE owner_uid = ?" to "driver-uid-42",
                ),
                verificationDeletes,
            )
            val capabilityDelete = callsFor("jdbcTemplate", "update")
                .single { it.args[0] == "DELETE FROM driver_capability_preferences WHERE driver_id = ?" }
            assertEquals(42L, capabilityDelete.args[1])

            // The original UID stays permanently tombstoned (V45) so a late eKYC decision
            // or a re-registration with the still-valid token cannot revive the account.
            val tombstoneInsert = callsFor("jdbcTemplate", "update")
                .single { (it.args[0] as? String)?.startsWith("INSERT INTO deleted_firebase_uids") == true }
            assertEquals("driver-uid-42", tombstoneInsert.args[1])

            assertEquals(
                listOf(
                    "driver-uid-42",
                    "DRIVER",
                    42L,
                    listOf("app_dat_xe/driver/42/avatar"),
                    listOf("capture-active", "capture-retired"),
                ),
                callsFor("outbox", "enqueue").single().args,
            )
            assertTrue(driver.firebaseUid.startsWith("deleted-driver-42-"))
            assertNull(driver.fullName)
            assertEquals("SUSPENDED", driver.verificationStatus)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `active rides still block deletion before any eKYC purge is enqueued`() {
        val terminalStatuses = RideStatus.entries.filter { it.terminal }
        `when`(driverRepository.findById(42L)).thenReturn(Optional.of(driver))
        `when`(rideRepository.existsByDriverIdAndStatusNotIn(42L, terminalStatuses)).thenReturn(true)
        calls.clear()

        assertThrows(ConflictException::class.java) { service().deleteAccount(actor) }

        assertTrue(
            callsFor("jdbcTemplate", "update").none {
                (it.args[0] as? String)?.startsWith("DELETE FROM driver_verification") == true
            },
        )
        assertTrue(callsFor("outbox", "enqueue").isEmpty())
    }
}
