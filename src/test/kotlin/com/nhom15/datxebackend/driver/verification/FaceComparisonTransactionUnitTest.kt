package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.ekyc.EkycClient
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FaceComparisonTransactionUnitTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val admin = AuthUser(99L, "uid-face-admin", Role.ADMIN, "Admin", null, null, "OFFLINE")

    /** Counts short transaction blocks and exposes their state so the HTTP stage can prove it is transaction-free. */
    private class CountingTransactionManager : PlatformTransactionManager {
        var executions = 0
            private set

        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
            executions++
            TransactionSynchronizationManager.initSynchronization()
            TransactionSynchronizationManager.setActualTransactionActive(true)
            return SimpleTransactionStatus()
        }

        override fun commit(status: TransactionStatus) {
            TransactionSynchronizationManager.setActualTransactionActive(false)
            TransactionSynchronizationManager.clearSynchronization()
        }

        override fun rollback(status: TransactionStatus) {
            TransactionSynchronizationManager.setActualTransactionActive(false)
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    private fun appResultSet(appId: UUID): ResultSet = mock(ResultSet::class.java).apply {
        `when`(getObject("id", UUID::class.java)).thenReturn(appId)
        `when`(getString("owner_uid")).thenReturn("uid-face-driver")
        `when`(getString("status")).thenReturn("UNDER_REVIEW")
        `when`(getLong("revision")).thenReturn(2L)
        `when`(getBoolean("confirmation_current")).thenReturn(true)
        `when`(getObject("submission_revision", Long::class.javaObjectType)).thenReturn(2L)
        `when`(getInt("resubmission_count")).thenReturn(0)
        `when`(getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.now()))
        `when`(getBoolean("identity_hold")).thenReturn(false)
    }

    private fun snapshotResultSet(snapshotJson: String): ResultSet = mock(ResultSet::class.java).apply {
        `when`(getString("snapshot_json")).thenReturn(snapshotJson)
    }

    private fun captureResultSet(captureId: UUID, appId: UUID): ResultSet = mock(ResultSet::class.java).apply {
        `when`(getObject("id", UUID::class.java)).thenReturn(captureId)
        `when`(getObject("application_id", UUID::class.java)).thenReturn(appId)
        `when`(getString("slot")).thenReturn("cccd_front")
        `when`(getString("storage_key")).thenReturn("key-cccd-front")
        `when`(getString("mime_type")).thenReturn("image/png")
        `when`(getInt("byte_size")).thenReturn(3)
        `when`(getString("sha256")).thenReturn("hash")
        `when`(getString("quality_json")).thenReturn("""{"warnings":[]}""")
        `when`(getLong("captured_revision")).thenReturn(1L)
    }

    private fun faceResult(): tools.jackson.databind.JsonNode {
        val result = mapper.createObjectNode()
        result.put("mode", "COMPARED")
        result.put("liveness", "LIVE")
        val pairs = mapper.createArrayNode()
        for ((left, right) in DriverVerificationWarnings.FACE_EXPECTED_PAIRS) {
            val pair = mapper.createObjectNode()
            pair.put("left", left)
            pair.put("right", right)
            pair.put("status", "MATCH")
            pairs.add(pair)
        }
        result.set("pairs", pairs)
        return result
    }

    @Test
    fun `evaluateFace runs between two short transactions without an active DB transaction`() {
        val appId = UUID.randomUUID()
        val captureId = UUID.randomUUID()
        val snapshotJson = mapper.writeValueAsString(
            linkedMapOf<String, Any?>(
                "captures" to mapOf("cccd_front" to mapOf("id" to captureId.toString())),
                "faceConsent" to mapOf("version" to "face-comparison-v1"),
                "revision" to 2L,
            ),
        )
        val jdbc = mock(
            JdbcTemplate::class.java,
            Answer { invocation ->
                if (invocation.method.returnType == Int::class.javaPrimitiveType) {
                    1
                } else {
                    Answers.RETURNS_DEFAULTS.answer(invocation)
                }
            },
        )
        `when`(
            jdbc.query(
                contains("driver_verification_applications"),
                any<RowMapper<Any>>(),
                any<Any>(),
            ),
        ).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val rowMapper = invocation.getArgument<RowMapper<Any>>(1)
            listOf(rowMapper.mapRow(appResultSet(appId), 0))
        }
        `when`(
            jdbc.query(
                contains("driver_verification_submissions"),
                any<RowMapper<Any>>(),
                any<Any>(),
                any<Any>(),
            ),
        ).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val rowMapper = invocation.getArgument<RowMapper<Any>>(1)
            listOf(rowMapper.mapRow(snapshotResultSet(snapshotJson), 0))
        }
        `when`(
            jdbc.query(
                contains("driver_verification_captures"),
                any<RowMapper<Any>>(),
                any<Any>(),
                any<Any>(),
            ),
        ).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val rowMapper = invocation.getArgument<RowMapper<Any>>(1)
            listOf(rowMapper.mapRow(captureResultSet(captureId, appId), 0))
        }

        val storage = mock(DriverVerificationStorageService::class.java)
        `when`(storage.read(anyString(), anyInt(), anyString())).thenReturn(byteArrayOf(1, 2, 3))
        val ekyc = mock(EkycClient::class.java)
        `when`(ekyc.faceComparisonEnabled).thenReturn(true)
        val result = faceResult()
        val activeDuringHttp = mutableListOf<Boolean>()
        `when`(ekyc.evaluateFace(anyMap(), anyBoolean())).thenAnswer {
            activeDuringHttp.add(TransactionSynchronizationManager.isActualTransactionActive())
            result
        }

        val txManager = CountingTransactionManager()
        val service = DriverVerificationWorkflowService(
            jdbc,
            mapper,
            storage,
            mock(VerificationProjectionRepository::class.java),
            mock(DriverRepository::class.java),
            ekycClient = ekyc,
            encryptionKey = "unit-encryption-key",
            lookupHmacKey = "unit-lookup-hmac-key",
            transactionManager = txManager,
        )

        assertEquals(result, service.faceComparison(admin, appId, 2L, 2L))
        verify(ekyc, times(1)).evaluateFace(anyMap(), anyBoolean())
        assertEquals(listOf(false), activeDuringHttp, "evaluateFace must not run inside a DB transaction")
        assertEquals(2, txManager.executions, "Expected short validate + write transactions only")
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive())
    }
}
