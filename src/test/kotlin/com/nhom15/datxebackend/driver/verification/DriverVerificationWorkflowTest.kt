package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.ekyc.EkycClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DriverVerificationWorkflowTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `protected storage requires a key and a provisioned directory`() {
        assertFailsWith<IllegalArgumentException> {
            DriverVerificationStorageService(tempDir.toString(), "", true)
        }
        assertFailsWith<IllegalArgumentException> {
            DriverVerificationStorageService(tempDir.resolve("missing").toString(), "synthetic-key", true)
        }
        assertFailsWith<IllegalArgumentException> {
            DriverVerificationStorageService("relative-captures", "synthetic-key", true)
        }
    }

    @Test
    fun `protected storage survives reinitialization and rejects old plaintext`() {
        val directory = tempDir.toString()
        val bytes = byteArrayOf(4, 5, 6)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val legacy = DriverVerificationStorageService(directory, "").store(bytes)
        val protected = DriverVerificationStorageService(directory, "synthetic-key", true)
        val encrypted = protected.store(bytes)
        val reopened = DriverVerificationStorageService(directory, "synthetic-key", true)
        assertTrue(reopened.read(encrypted, bytes.size, hash).contentEquals(bytes))
        assertEquals("VERIFICATION_UNAVAILABLE", assertFailsWith<VerificationWorkflowException> {
            reopened.read(legacy, bytes.size, hash)
        }.errorCode)
    }

    @Test
    fun `image validation rejects empty input and encoded input over the size limit`() {
        val storage = DriverVerificationStorageService(tempDir.toString(), "")
        assertEquals("INVALID_IMAGE", assertFailsWith<VerificationWorkflowException> {
            storage.inspectImage(byteArrayOf(), "image/png")
        }.errorCode)
        assertEquals("IMAGE_TOO_LARGE", assertFailsWith<VerificationWorkflowException> {
            storage.inspectImage(ByteArray(8_000_001), "image/png")
        }.errorCode)
    }

    @Test
    fun `storage validates png image roundtrip with optional aes encryption and rejects invalid bytes`() {
        val storage = DriverVerificationStorageService(tempDir.toString(), "unit-test-encryption-key-material-32")
        val image = BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run {
                color = Color.WHITE
                fillRect(0, 0, 600, 400)
                dispose()
            }
        }
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, "png", out))
        val bytes = out.toByteArray()

        val checked = storage.inspectImage(bytes, "image/png")
        assertEquals(600, checked.width)
        assertEquals(400, checked.height)
        assertEquals(listOf("SMALL"), checked.warnings)

        val key = storage.store(bytes)
        val restored = storage.read(key, checked.size, checked.sha256)
        assertTrue(bytes.contentEquals(restored))

        assertFailsWith<VerificationWorkflowException> {
            storage.inspectImage(byteArrayOf(1, 2, 3), "image/jpeg")
        }
    }

    @Test
    fun `warnings support unlimited expiry GPLX without flagging GPLX_EXPIRY_UNKNOWN`() {
        val payload = mapOf(
            "captures" to mapOf(
                "cccd_front" to mapOf("id" to "1", "quality" to mapOf("warnings" to emptyList<String>())),
                "cccd_back" to mapOf("id" to "2", "quality" to mapOf("warnings" to emptyList<String>())),
                "gplx_front" to mapOf("id" to "3", "quality" to mapOf("warnings" to emptyList<String>())),
                "selfie" to mapOf("id" to "4", "quality" to mapOf("warnings" to emptyList<String>())),
            ),
            "proposals" to emptyMap<String, Any>(),
            "confirmed" to mapOf(
                "cccd_name" to "Nguyễn Văn Mẫu",
                "cccd_id" to "000000000001",
                "cccd_dob" to "2000-01-01",
                "cccd_expiry_date" to "2035-01-01",
                "gplx_number" to "250256000001",
                "gplx_name" to "Nguyễn Văn Mẫu",
                "gplx_dob" to "2000-01-01",
                "gplx_classes" to listOf("A1"),
                "no_expiry" to true,
                "expiry_date" to null,
            ),
        )
        val warnings = DriverVerificationWarnings.build(payload, 4)
        assertTrue(warnings.none { it.code == "GPLX_EXPIRY_UNKNOWN" || it.code == "GPLX_EXPIRED" })
        assertTrue(warnings.none { it.blocksActivation })
    }

    @Test
    fun `controller exposes unified driver and admin endpoints with workflow error envelope`() {
        val service = mock(DriverVerificationWorkflowService::class.java)
        val controller = DriverVerificationWorkflowController(service)
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

        val driver = AuthUser(2, "uid-driver-2", Role.DRIVER, "Test Driver", null, "+84999999999", "OFFLINE")
        val appId = UUID.randomUUID()
        `when`(service.me(driver)).thenReturn(mapOf("uid" to driver.firebaseUid, "admin" to false))
        `when`(service.extractSlot(driver, appId, "cccd_front", 1L))
            .thenThrow(VerificationWorkflowException(504, "ENGINE_TIMEOUT", "Đọc ảnh quá thời gian."))

        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(driver, null)
        try {
            mvc.perform(get("/ekyc/driver-verification/v1/me"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.uid").value("uid-driver-2"))

            mvc.perform(
                post("/api/driver/verification/v1/applications/$appId/extract/cccd_front")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"revision":1}"""),
            )
                .andExpect(status().isGatewayTimeout)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("ENGINE_TIMEOUT"))
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `service me advertises faceConsentVersion only when faceComparisonEnabled is true`() {
        val jdbc = mock(JdbcTemplate::class.java)
        val mapper = JsonMapper.builder().build()
        val storage = DriverVerificationStorageService(tempDir.toString(), "")
        val projection = mock(VerificationProjectionRepository::class.java)
        val drivers = mock(DriverRepository::class.java)
        val actor = AuthUser(1, "uid-1", Role.DRIVER, null, null, null, "OFFLINE")

        val offClient = EkycClient(RestClient.builder().baseUrl("https://fixture.modal.run").build(), mapper, faceComparisonEnabled = false)
        val svcOff = DriverVerificationWorkflowService(jdbc, mapper, storage, projection, drivers, ekycClient = offClient)
        assertEquals(false, svcOff.me(actor).containsKey("faceConsentVersion"))

        val onClient = EkycClient(RestClient.builder().baseUrl("https://fixture.modal.run").build(), mapper, faceComparisonEnabled = true)
        val svcOn = DriverVerificationWorkflowService(jdbc, mapper, storage, projection, drivers, ekycClient = onClient)
        assertEquals("face-comparison-v1", svcOn.me(actor)["faceConsentVersion"])
    }
}
