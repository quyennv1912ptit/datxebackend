package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CccdQrBackendTest {
    private val today = LocalDate.of(2026, 11, 19)

    @Test
    fun `parses CCCD 2021, Can Cuoc 2024 with under-14 issue date, and 6-field variants`() {
        val p2021 = CccdQrParser.parse(
            "046096003093|191234567|NGUYỄN VĂN MẪU|01011996|Nam|Số 1 Đường Giải Phóng, Hà Nội|01052021",
            "cccd_front",
            today,
        )
        assertEquals(CccdQrStatus.DECODED_VALID, p2021.status)
        assertEquals("046096003093", p2021.fields["id_number"])
        assertEquals("NGUYỄN VĂN MẪU", p2021.fields["full_name"])
        assertEquals("1996-01-01", p2021.fields["dob"])
        assertEquals("MALE", p2021.fields["gender"])
        assertEquals("2021-05-01", p2021.fields["issue_date"])
        assertFalse(p2021.fields.containsKey("expiry"))
        assertFalse(p2021.fields.containsKey("old_cmnd"))

        val p2024Child = CccdQrParser.parse(
            "046222000123||TRẦN BÉ MẪU|01/05/2022|Nữ|TP.HCM|15/07/2024|046190000001|TRẦN VĂN CHA",
            "cccd_back",
            today,
        )
        assertEquals(CccdQrStatus.DECODED_VALID, p2024Child.status)
        assertEquals("2022-05-01", p2024Child.fields["dob"])
        assertEquals("2024-07-15", p2024Child.fields["issue_date"])
        assertEquals("FEMALE", p2024Child.fields["gender"])
        assertEquals(setOf("id_number", "full_name", "dob", "gender", "address", "issue_date"), p2024Child.fields.keys)

        val p6 = CccdQrParser.parse(
            "046096003094|LÊ THỊ MẪU|02031995|Nữ|Đà Nẵng|10082024",
            "cccd_back",
            today,
        )
        assertEquals(CccdQrStatus.DECODED_VALID, p6.status)
        assertEquals("LÊ THỊ MẪU", p6.fields["full_name"])
    }

    @Test
    fun `handles missing fields, invalid format, and unsupported format`() {
        val partial = CccdQrParser.parse("046096003093||NGUYỄN VĂN MẪU|01011996|||", "cccd_front", today)
        assertEquals(CccdQrStatus.DECODED_VALID, partial.status)
        assertEquals(listOf("gender", "address", "issue_date"), partial.missingFields)
        assertTrue("QR_PARTIAL_FIELDS" in partial.warnings)

        assertEquals(
            CccdQrStatus.INVALID_FORMAT,
            CccdQrParser.parse("04609600309||NGUYỄN VĂN MẪU|01011996|Nam|HN|01052021", "cccd_front", today).status,
        )
        assertEquals(
            CccdQrStatus.INVALID_FORMAT,
            CccdQrParser.parse("046096003093||NGUYỄN VĂN MẪU|31021996|Nam|HN|01052021", "cccd_front", today).status,
        )
        assertEquals(
            CccdQrStatus.INVALID_FORMAT,
            CccdQrParser.parse("046096003093||NGUYỄN VĂN MẪU|01052020|Nam|HN|01052019", "cccd_front", today).status,
        )
        assertEquals(
            CccdQrStatus.UNSUPPORTED_FORMAT,
            CccdQrParser.parse("https://example.invalid/qr", "cccd_front", today).status,
        )
    }

    @Test
    fun `cross validates QR vs OCR across slots, distinguishes diacritics, and rejects fuzzy ID matches`() {
        val summary = CccdQrParser.compareQrAndOcr(
            mapOf("id_number" to "046096003093", "dob" to "1996-01-01", "full_name" to "NGUYỄN VĂN MẪU"),
            mapOf("id_number" to "046096003094", "dob" to "01/01/1996", "full_name" to "NGUYEN VAN MAU"),
        )
        assertEquals("CONFLICT", summary.status)
        assertEquals(listOf("id_number", "full_name"), summary.conflictFields)
        assertEquals(listOf("full_name"), summary.diacriticMismatchFields)
        assertEquals(listOf("dob"), summary.matchedFields)

        val payload = mapOf(
            "captures" to mapOf(
                "cccd_front" to mapOf("id" to "cap-front"),
                "cccd_back" to mapOf("id" to "cap-back"),
                "gplx_front" to mapOf("id" to "cap-gplx"),
                "selfie" to mapOf("id" to "cap-selfie"),
            ),
            "proposals" to mapOf(
                "cccd_front" to mapOf(
                    "captureId" to "cap-front",
                    "result" to mapOf(
                        "ocrStatus" to "OCR_READ",
                        "candidates" to listOf(
                            mapOf("fieldKey" to "id_number", "candidateValue" to "046096003099", "source" to "OCR", "parseStatus" to "parsed", "validationStatus" to "valid"),
                            mapOf("fieldKey" to "full_name", "candidateValue" to "NGUYEN VAN MAU", "source" to "OCR", "parseStatus" to "parsed", "validationStatus" to "valid"),
                            mapOf("fieldKey" to "dob", "candidateValue" to "1996-01-01", "source" to "OCR", "parseStatus" to "parsed", "validationStatus" to "valid"),
                        ),
                    ),
                ),
            ),
            "qrLiveScan" to mapOf(
                "status" to "DECODED_VALID",
                "source" to "QR_LIVE_SCAN",
                "documentSide" to "BACK",
                "boundToCapture" to false,
                "fields" to mapOf(
                    "id_number" to "046096003093",
                    "full_name" to "NGUYỄN VĂN MẪU",
                    "dob" to "1996-01-01",
                ),
            ),
        )
        val warnings = DriverVerificationWarnings.build(
            payload = payload,
            revision = 4L,
            identityContext = IdentityVerificationContext(lockedCccdId = "046096003099"),
        )
        val codes = warnings.map { it.code to it.fieldKey }.toSet()
        assertTrue(("QR_OCR_CONFLICT" to "cccd_id") in codes)
        assertTrue(("QR_OCR_NAME_DIACRITIC_MISMATCH" to "cccd_name") in codes)
        assertTrue(("QR_LIVE_SCAN_UNBOUND" to null) in codes)
        assertTrue(("LOCKED_CCCD_ID_CHANGED" to "cccd_id") in codes)
    }

    @Test
    fun `hmac lookup is deterministic per key and does not leak id number`() {
        val key1 = "server-secret-key-one-32-bytes!!".toByteArray()
        val key2 = "server-secret-key-two-32-bytes!!".toByteArray()
        val h1 = CccdQrParser.computeLookupHmac("046096003093", key1)
        val h1Again = CccdQrParser.computeLookupHmac(" 046096003093 ", key1)
        val h2 = CccdQrParser.computeLookupHmac("046096003093", key2)
        assertEquals(h1, h1Again)
        assertNotEquals(h1, h2)
        assertEquals(64, h1.length)
        assertFalse("046096003093" in h1)
    }

    @Test
    fun `qr-scan controller endpoint maps revision conflict and valid response`() {
        val service = mock(DriverVerificationWorkflowService::class.java)
        val controller = DriverVerificationWorkflowController(service)
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        val driver = AuthUser(5, "uid-driver-5", Role.DRIVER, "Driver 5", null, "+84911111111", "OFFLINE")
        val appId = UUID.randomUUID()
        val validBody = QrScanBody(revision = 2L, rawPayload = "046096003093||NGUYỄN VĂN MẪU|01011996|Nam|HN|01052021", documentSide = "FRONT")
        `when`(service.submitQrLiveScan(driver, appId, validBody)).thenReturn(
            mapOf(
                "id" to appId.toString(),
                "revision" to 3L,
                "qr" to mapOf("status" to "DECODED_VALID", "source" to "QR_LIVE_SCAN", "boundToCapture" to false),
            ),
        )
        val staleBody = QrScanBody(revision = 1L, rawPayload = "046096003093||NGUYỄN VĂN MẪU|01011996|Nam|HN|01052021")
        `when`(service.submitQrLiveScan(driver, appId, staleBody))
            .thenThrow(VerificationWorkflowException(409, "REVISION_CONFLICT", "Hồ sơ đã thay đổi."))

        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(driver, null)
        try {
            mvc.perform(
                post("/api/driver/verification/v1/applications/$appId/qr-scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"revision":2,"rawPayload":"046096003093||NGUYỄN VĂN MẪU|01011996|Nam|HN|01052021","documentSide":"FRONT"}"""),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.revision").value(3))
                .andExpect(jsonPath("$.data.qr.source").value("QR_LIVE_SCAN"))
                .andExpect(jsonPath("$.data.qr.boundToCapture").value(false))

            mvc.perform(
                post("/ekyc/driver-verification/v1/applications/$appId/qr-scan")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"revision":1,"rawPayload":"046096003093||NGUYỄN VĂN MẪU|01011996|Nam|HN|01052021"}"""),
            )
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("REVISION_CONFLICT"))
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `parses GPLX PET QR formats, cross validates vehicle classes, and enforces required gplx_back`() {
        val today = LocalDate.of(2026, 11, 19)
        val pipeGplx = CccdQrParser.parse(
            "790123456789|NGUYỄN VĂN MẪU|01/01/1996|A1, B2|Sở GTVT TP.HCM",
            slot = "gplx_back",
            today = today,
        )
        assertEquals(CccdQrStatus.DECODED_VALID, pipeGplx.status)
        assertEquals("790123456789", pipeGplx.fields["id_number"])
        assertEquals("1996-01-01", pipeGplx.fields["dob"])
        assertEquals(listOf("A1", "B2"), pipeGplx.fields["vehicle_class"])

        val fourFieldGplx = CccdQrParser.parse(
            "NGUYỄN VĂN MẪU|01/01/1996|B2|Sở GTVT Hà Nội",
            slot = "gplx_back",
            today = today,
        )
        assertEquals(CccdQrStatus.DECODED_VALID, fourFieldGplx.status)
        assertTrue("id_number" in fourFieldGplx.missingFields)
        assertEquals(listOf("B2"), fourFieldGplx.fields["vehicle_class"])

        assertEquals(
            CccdQrStatus.UNSUPPORTED_FORMAT,
            CccdQrParser.parse("https://gplx.gov.vn/default.aspx?s=abc", slot = "gplx_back", today = today).status,
        )

        val gplxConflict = CccdQrParser.compareQrAndOcr(
            mapOf("full_name" to "NGUYỄN VĂN MẪU", "dob" to "1996-01-01", "vehicle_class" to listOf("A1", "B2")),
            mapOf("full_name" to "NGUYỄN VĂN MẪU", "dob" to "1996-01-01", "vehicle_class" to listOf("A1")),
        )
        assertEquals("CONFLICT", gplxConflict.status)
        assertEquals(listOf("vehicle_class"), gplxConflict.conflictFields)

        assertTrue("gplx_back" in DriverVerificationWarnings.REQUIRED_SLOTS)
    }
}
