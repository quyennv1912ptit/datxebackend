package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.driver.repository.DriverRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

class EkycIdentityLockAndKeyLifecycleTests {

    @TempDir
    lateinit var tempDir: Path

    private val mapper: ObjectMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .findAndAddModules()
        .build()

    private fun testPng(): ByteArray {
        val image = BufferedImage(600, 480, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run {
                color = Color.WHITE
                fillRect(0, 0, 600, 480)
                dispose()
            }
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private class StubJdbcTemplate(
        private val onQuery: (sql: String, args: Array<out Any?>) -> List<Any?>,
    ) : JdbcTemplate() {
        var lastSql: String? = null
        @Suppress("UNCHECKED_CAST")
        override fun <T> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): MutableList<T> {
            lastSql = sql
            return onQuery(sql, args).toMutableList() as MutableList<T>
        }
    }

    @Test
    fun `F-017 duplicate CCCD and GPLX SQL filters out DRAFT, REJECTED, and REVOKED rows`() {
        val jdbc = StubJdbcTemplate { _, _ -> emptyList() }
        val storage = DriverVerificationStorageService(tempDir.toString(), "test-key-v1")
        val projection = Mockito.mock(VerificationProjectionRepository::class.java)
        val drivers = Mockito.mock(DriverRepository::class.java)
        val svc = DriverVerificationWorkflowService(jdbc, mapper, storage, projection, drivers, lookupHmacKey = "test-hmac-key")

        val cccdDup = invokePrivateBoolean(svc, "hasDuplicateCccdAcrossAccounts", "uid-b", "001206000099")
        assertThat(cccdDup).isFalse()

        val cccdSql = jdbc.lastSql!!.replace(Regex("\\s+"), " ")
        assertThat(cccdSql).contains("AND status IN ('APPROVED', 'SUBMITTED', 'UNDER_REVIEW', 'NEEDS_MORE_INFO')")
        assertThat(cccdSql).doesNotContain("OR status IN")

        val gplxDup = invokePrivateBoolean(svc, "hasDuplicateGplxAcrossAccounts", "uid-b", "790123456789")
        assertThat(gplxDup).isFalse()

        val gplxSql = jdbc.lastSql!!.replace(Regex("\\s+"), " ")
        assertThat(gplxSql).contains("AND status IN ('APPROVED', 'SUBMITTED', 'UNDER_REVIEW', 'NEEDS_MORE_INFO')")
        assertThat(gplxSql).doesNotContain("OR status IN")
    }

    @Test
    fun `F-017 duplicate CCCD and GPLX checks fail closed on database query failure`() {
        val jdbc = StubJdbcTemplate { _, _ -> throw RuntimeException("DB connection timeout") }
        val storage = DriverVerificationStorageService(tempDir.toString(), "test-key-v1")
        val projection = Mockito.mock(VerificationProjectionRepository::class.java)
        val drivers = Mockito.mock(DriverRepository::class.java)
        val svc = DriverVerificationWorkflowService(jdbc, mapper, storage, projection, drivers, lookupHmacKey = "test-hmac-key")

        assertThatThrownBy { invokePrivateBoolean(svc, "hasDuplicateCccdAcrossAccounts", "uid-b", "001206000099") }
            .isInstanceOf(VerificationWorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo("VERIFICATION_UNAVAILABLE")

        assertThatThrownBy { invokePrivateBoolean(svc, "hasDuplicateGplxAcrossAccounts", "uid-b", "790123456789") }
            .isInstanceOf(VerificationWorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo("VERIFICATION_UNAVAILABLE")
    }

    @Test
    fun `F-017 detects cross-account CCCD and GPLX duplicates on active or approved accounts`() {
        val storage = DriverVerificationStorageService(tempDir.toString(), "test-key-v1")
        val projection = Mockito.mock(VerificationProjectionRepository::class.java)
        val drivers = Mockito.mock(DriverRepository::class.java)

        val cccdJdbc = StubJdbcTemplate { _, _ -> listOf(listOf("001206000099", null, null, null)) }
        val cccdSvc = DriverVerificationWorkflowService(cccdJdbc, mapper, storage, projection, drivers, lookupHmacKey = "test-hmac-key")
        assertThat(invokePrivateBoolean(cccdSvc, "hasDuplicateCccdAcrossAccounts", "uid-b", "001206000099")).isTrue()

        val gplxJdbc = StubJdbcTemplate { _, _ -> listOf(Triple("790123456789", "SUBMITTED", null)) }
        val gplxSvc = DriverVerificationWorkflowService(gplxJdbc, mapper, storage, projection, drivers, lookupHmacKey = "test-hmac-key")
        assertThat(invokePrivateBoolean(gplxSvc, "hasDuplicateGplxAcrossAccounts", "uid-b", "790123456789")).isTrue()
    }

    @Test
    fun `F-019 lookup HMAC key is deterministic in dev mode, domain-separated from AES key, and fails fast when blank in protected mode`() {
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        val storage = DriverVerificationStorageService(tempDir.toString(), "shared-secret")
        val projection = Mockito.mock(VerificationProjectionRepository::class.java)
        val drivers = Mockito.mock(DriverRepository::class.java)

        // Two instances with blank key in dev mode must NOT generate different ephemeral SecureRandom secrets
        val dev1 = DriverVerificationWorkflowService(jdbc, mapper, storage, projection, drivers, lookupHmacKey = "")
        val dev2 = DriverVerificationWorkflowService(jdbc, mapper, storage, projection, drivers, lookupHmacKey = "")
        val secret1 = extractHmacSecret(dev1)
        val secret2 = extractHmacSecret(dev2)
        assertThat(secret1).isEqualTo(secret2)

        // Domain separation: HMAC secret derived from shared-secret must NOT equal raw SHA-256(shared-secret) used by AES
        val withKey = DriverVerificationWorkflowService(jdbc, mapper, storage, projection, drivers, lookupHmacKey = "shared-secret")
        val rawSha256 = MessageDigest.getInstance("SHA-256").digest("shared-secret".toByteArray(StandardCharsets.UTF_8))
        assertThat(extractHmacSecret(withKey)).isNotEqualTo(rawSha256)

        // Fail-fast in protected/production mode when HMAC key is blank
        assertThatThrownBy {
            DriverVerificationWorkflowService(
                jdbc, mapper, storage, projection, drivers,
                lookupHmacKey = "   ",
                requireProtectedStorage = true,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `F-019 storage service supports encryption key rotation and fails closed when decrypt key is missing`() {
        val bytes = testPng()
        val oldStorage = DriverVerificationStorageService(tempDir.toString(), "old-encryption-key-v1", false)
        val checked = oldStorage.inspectImage(bytes, "image/png")
        val legacyObjectKey = oldStorage.store(bytes)

        // Rotate to new key while keeping old key in previousEncryptionKeys -> must still decrypt legacy capture
        val rotatedWithPrevious = DriverVerificationStorageService(
            tempDir.toString(),
            "new-encryption-key-v2",
            false,
            previousEncryptionKeys = "old-encryption-key-v1",
        )
        assertThat(rotatedWithPrevious.read(legacyObjectKey, checked.size, checked.sha256)).isEqualTo(bytes)

        // New captures stored with rotatedStorage use new primary key
        val newObjectKey = rotatedWithPrevious.store(bytes)
        assertThat(rotatedWithPrevious.read(newObjectKey, checked.size, checked.sha256)).isEqualTo(bytes)

        // Without previousEncryptionKeys, reading legacy capture fails closed with VerificationWorkflowException(503)
        val rotatedWithoutPrevious = DriverVerificationStorageService(
            tempDir.toString(),
            "new-encryption-key-v2",
            false,
        )
        assertThatThrownBy { rotatedWithoutPrevious.read(legacyObjectKey, checked.size, checked.sha256) }
            .isInstanceOf(VerificationWorkflowException::class.java)
            .extracting("httpStatus")
            .isEqualTo(503)
    }

    @Test
    fun `F-019 duplicate CCCD HMAC lookup stays backward-compatible with pre-domain-separation HMAC and rotated keys`() {
        val storage = DriverVerificationStorageService(tempDir.toString(), "shared-secret")
        val projection = Mockito.mock(VerificationProjectionRepository::class.java)
        val drivers = Mockito.mock(DriverRepository::class.java)

        val cccd = "001206000099"
        val legacyUnprefixedSecret = MessageDigest.getInstance("SHA-256")
            .digest("shared-secret".toByteArray(StandardCharsets.UTF_8))
        val legacyStoredHmac = CccdQrParser.computeLookupHmac(cccd, legacyUnprefixedSecret)

        val legacyJdbc = StubJdbcTemplate { _, _ -> listOf(listOf(null, null, legacyStoredHmac, null)) }
        val svcUpgraded = DriverVerificationWorkflowService(
            legacyJdbc, mapper, storage, projection, drivers,
            lookupHmacKey = "shared-secret",
        )

        assertThat(invokePrivateBoolean(svcUpgraded, "hasDuplicateCccdAcrossAccounts", "uid-b", cccd))
            .describedAs("Legacy pre-Batch-2B HMAC must still match to avoid duplicate-detection gap on upgrade")
            .isTrue()

        val prevDomainSecret = MessageDigest.getInstance("SHA-256")
            .digest("ptitgo:verification:lookup-hmac:v1:shared-secret".toByteArray(StandardCharsets.UTF_8))
        val prevDomainHmac = CccdQrParser.computeLookupHmac(cccd, prevDomainSecret)
        val rotatedJdbc = StubJdbcTemplate { _, _ -> listOf(listOf(null, null, null, prevDomainHmac)) }
        val svcRotated = DriverVerificationWorkflowService(
            rotatedJdbc, mapper, storage, projection, drivers,
            lookupHmacKey = "new-hmac-v2",
            previousLookupHmacKeys = "shared-secret",
        )

        assertThat(invokePrivateBoolean(svcRotated, "hasDuplicateCccdAcrossAccounts", "uid-b", cccd))
            .describedAs("Rotated previous HMAC key must still match qr_cccd_id_hmac without duplicate-detection gap")
            .isTrue()
    }

    private fun invokePrivateBoolean(target: Any, methodName: String, arg1: String, arg2: String?): Boolean {
        val method = target.javaClass.getDeclaredMethod(methodName, String::class.java, String::class.java)
        method.isAccessible = true
        return try {
            method.invoke(target, arg1, arg2) as Boolean
        } catch (ex: java.lang.reflect.InvocationTargetException) {
            throw ex.targetException
        }
    }

    private fun extractHmacSecret(target: DriverVerificationWorkflowService): ByteArray {
        val field = DriverVerificationWorkflowService::class.java.getDeclaredField("lookupHmacSecret")
        field.isAccessible = true
        return field.get(target) as ByteArray
    }
}
