package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.driver.dto.UpdateDriverIdentityRequest
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.service.DriverProfileService
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerificationAuditFindingsUnitTest {
    private val mapper = JsonMapper.builder().build()

    private fun faceResult(statuses: List<String>, includeQuality: Boolean = true): JsonNode {
        val result = mapper.createObjectNode()
        result.put("mode", "COMPARED")
        result.put("liveness", "LIVE")
        val pairs = mapper.createArrayNode()
        val expected = DriverVerificationWarnings.FACE_EXPECTED_PAIRS
        for ((index, pair) in expected.withIndex()) {
            val item = mapper.createObjectNode()
            item.put("left", pair.first)
            item.put("right", pair.second)
            item.put("status", statuses[index % statuses.size])
            pairs.add(item)
        }
        result.set("pairs", pairs)
        if (includeQuality) {
            val quality = mapper.createObjectNode()
            quality.put("selfie", "FACE_BLURRY")
            result.set("quality", quality)
        }
        return result
    }

    @Test
    fun `face comparison signals require every expected pair and normalize statuses and quality`() {
        val signals = DriverVerificationWarnings.normalizedFaceSignals(
            faceResult(listOf("MATCH", "POSSIBLE_MISMATCH", "MATCH")),
        )
        assertEquals(listOf("MATCH", "POSSIBLE_MISMATCH"), signals.statuses)
        assertEquals(mapOf("selfie" to "FACE_BLURRY"), signals.quality)

        val incomplete = mapper.createObjectNode()
        val pairs = mapper.createArrayNode()
        for (pair in DriverVerificationWarnings.FACE_EXPECTED_PAIRS.take(2)) {
            val item = mapper.createObjectNode()
            item.put("left", pair.first)
            item.put("right", pair.second)
            item.put("status", "MATCH")
            pairs.add(item)
        }
        incomplete.set("pairs", pairs)
        val failure = assertFailsWith<VerificationWorkflowException> {
            DriverVerificationWarnings.normalizedFaceSignals(incomplete)
        }
        assertEquals(502, failure.httpStatus)
        assertEquals("EKYC_UNAVAILABLE", failure.errorCode)
    }

    @Test
    fun `possible face mismatch blocks activation once comparison is evaluated`() {
        val warnings = DriverVerificationWarnings.build(
            payload = emptyMap(),
            revision = 3L,
            faceStatuses = listOf("POSSIBLE_MISMATCH"),
        )
        val mismatch = warnings.single { it.code == "FACE_POSSIBLE_MISMATCH" }
        assertEquals("CRITICAL", mismatch.severity)
        assertTrue(mismatch.blocksActivation)
        assertFalse(warnings.any { it.code == "FACE_NOT_EVALUATED" })

        val unevaluated = DriverVerificationWarnings.build(emptyMap(), 3L)
        val notEvaluated = unevaluated.single { it.code == "FACE_NOT_EVALUATED" }
        assertFalse(notEvaluated.blocksActivation)
        assertEquals("INFO", notEvaluated.severity)
    }

    @Test
    fun `live qr is preserved when replacing a different unknown-side document`() {
        val service = DriverVerificationWorkflowService(
            mock(JdbcTemplate::class.java),
            mapper,
            mock(DriverVerificationStorageService::class.java),
            mock(VerificationProjectionRepository::class.java),
            mock(DriverRepository::class.java),
        )
        val bound = UUID.randomUUID()
        val other = UUID.randomUUID()
        assertTrue(
            service.shouldClearQrLiveScan(
                """{"attachmentId":"$bound","documentSide":"UNKNOWN"}""",
                "cccd_front", bound, "cccd",
            ),
        )
        assertFalse(
            service.shouldClearQrLiveScan(
                """{"attachmentId":"$other","documentSide":"UNKNOWN"}""",
                "cccd_back", bound, "cccd",
            ),
        )
        assertFalse(
            service.shouldClearQrLiveScan(
                """{"attachmentId":"$other"}""",
                "cccd_front", bound, "cccd",
            ),
        )
        assertFalse(
            service.shouldClearQrLiveScan(
                """{"attachmentId":"$other","documentSide":"FRONT"}""",
                "cccd_back", bound, "cccd",
            ),
        )
        assertTrue(
            service.shouldClearQrLiveScan(
                """{"attachmentId":"$other","documentSide":"BACK"}""",
                "cccd_back", bound, "cccd",
            ),
        )
    }

    @Test
    fun `approved driver cannot rename identity through the ordinary profile endpoint`() {
        val driver = Driver().apply {
            id = 7L
            firebaseUid = "driver-lock-7"
            verificationStatus = "APPROVED"
            fullName = "NGUYEN VAN A"
        }
        val repo = mock(DriverRepository::class.java)
        `when`(repo.findByIdForUpdate(7L)).thenReturn(Optional.of(driver))
        val jdbc = mock(JdbcTemplate::class.java)
        `when`(jdbc.query(anyString(), any<RowMapper<Any>>(), any<Any>()))
            .thenReturn(mutableListOf<Any>("NGUYEN VAN A"))
        val service = DriverProfileService(
            repo,
            mock(AuthenticatedUserCache::class.java),
            mock(CloudinaryAssetService::class.java),
            jdbc,
        )
        TransactionSynchronizationManager.initSynchronization()
        try {
            val failure = assertFailsWith<AppException> {
                service.updateIdentity(7L, UpdateDriverIdentityRequest("TRAN VAN B"))
            }
            assertEquals(ErrorCode.CONFLICT, failure.errorCode)
            assertEquals(409, failure.httpStatus.value())
            assertEquals("NGUYEN VAN A", driver.fullName)

            // Diacritics/case are the same approved identity, so this stays allowed.
            service.updateIdentity(7L, UpdateDriverIdentityRequest("Nguyễn Văn A", bankName = "VCB"))
            assertEquals("Nguyễn Văn A", driver.fullName)
            assertEquals("VCB", driver.bankName)

            // Pending drivers remain freely editable.
            driver.verificationStatus = "PENDING"
            service.updateIdentity(7L, UpdateDriverIdentityRequest("TRAN VAN B"))
            assertEquals("TRAN VAN B", driver.fullName)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }
}
