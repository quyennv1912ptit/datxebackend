package com.nhom15.datxebackend.ekyc

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.exception.GlobalExceptionHandler
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EkycClientTest {
    private val builder = RestClient.builder().baseUrl("https://fixture.modal.run")
    private val mock = MockRestServiceServer.bindTo(builder).build()
    private val client = EkycClient(builder.build(), JsonMapper.builder().build())

    @Test
    fun `forwards image to the matching extraction slot and returns proposal data`() {
        val image = byteArrayOf(1, 2, 3)
        mock.expect(requestTo("https://fixture.modal.run/ekyc/documents/gplx_front/extract"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.IMAGE_PNG))
            .andExpect(content().bytes(image))
            .andRespond(
                withSuccess(
                    """{"success":true,"data":{"slot":"gplx_front","qrStatus":"QR_NOT_FOUND","candidates":[]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val result = client.extract(EkycDocumentSlot.GPLX_FRONT, MediaType.IMAGE_PNG, image.inputStream())
        assertEquals("gplx_front", result.path("slot").asString())
        assertEquals("QR_NOT_FOUND", result.path("qrStatus").asString())
        mock.verify()
    }

    @Test
    fun `preserves timeout even when the platform returns a non JSON body`() {
        mock.expect(requestTo("https://fixture.modal.run/ekyc/documents/cccd_front/extract"))
            .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT).body("gateway response"))
        val error = assertFailsWith<AppException> {
            client.extract(EkycDocumentSlot.CCCD_FRONT, MediaType.IMAGE_JPEG, byteArrayOf(1).inputStream())
        }
        assertEquals(ErrorCode.EKYC_TIMEOUT, error.errorCode)
        mock.verify()
    }

    @Test
    fun `does not forward an oversized image without a declared length`() {
        val error = assertFailsWith<AppException> {
            client.extract(
                EkycDocumentSlot.CCCD_FRONT,
                MediaType.IMAGE_JPEG,
                ByteArray(MAX_IMAGE_BYTES + 1).inputStream(),
            )
        }
        assertEquals(ErrorCode.EKYC_IMAGE_TOO_LARGE, error.errorCode)
        mock.verify()
    }

    @Test
    fun `rejects unsupported slots and media without an upstream call`() {
        val mvc = MockMvcBuilders.standaloneSetup(EkycController(client))
            .setControllerAdvice(GlobalExceptionHandler()).build()
        mvc.perform(post("/api/driver/ekyc/documents/selfie/extract")
            .contentType(MediaType.IMAGE_JPEG).content(byteArrayOf(1)))
            .andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(post("/api/driver/ekyc/documents/cccd_back/extract")
            .contentType(MediaType.APPLICATION_PDF).content(byteArrayOf(1)))
            .andExpect(status().isBadRequest)
        mock.verify()
    }

    @Test
    fun `retries once on transient 503 and caches identical image extraction by sha256`() {
        val image = byteArrayOf(7, 8, 9)
        mock.expect(requestTo("https://fixture.modal.run/ekyc/documents/cccd_front/extract"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        mock.expect(requestTo("https://fixture.modal.run/ekyc/documents/cccd_front/extract"))
            .andRespond(
                withSuccess(
                    """{"success":true,"data":{"slot":"cccd_front","qrStatus":"QR_FOUND_SUPPORTED","candidates":[]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val first = client.extract(EkycDocumentSlot.CCCD_FRONT, MediaType.IMAGE_JPEG, image.inputStream())
        val second = client.extract(EkycDocumentSlot.CCCD_FRONT, MediaType.IMAGE_JPEG, image.inputStream())
        assertEquals("QR_FOUND_SUPPORTED", first.path("qrStatus").asString())
        assertEquals(first, second)
        mock.verify()
    }

    @Test
    fun `stops retrying once the bounded total deadline is reached`() {
        val localBuilder = RestClient.builder().baseUrl("https://fixture.modal.run")
        val localMock = MockRestServiceServer.bindTo(localBuilder).build()
        val strictClient = EkycClient(
            upstream = localBuilder.build(),
            mapper = JsonMapper.builder().build(),
            maxRetries = 2,
            faceComparisonEnabled = false,
            totalDeadlineMs = 100L,
        )
        localMock.expect(requestTo("https://fixture.modal.run/ekyc/documents/cccd_front/extract"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        val err = assertFailsWith<AppException> {
            strictClient.extract(EkycDocumentSlot.CCCD_FRONT, MediaType.IMAGE_JPEG, byteArrayOf(4, 5, 6).inputStream())
        }
        assertEquals(ErrorCode.EKYC_BUSY, err.errorCode)
        localMock.verify()
    }

    @Test
    fun `config enforces 65s read timeout, 75s deadline, 90s cap and skips retry when attempt timeout would exceed deadline`() {
        val cfg = EkycConfig()
        assertEquals(65_000L, cfg.readTimeoutMs)
        assertEquals(75_000L, cfg.totalDeadlineMs)

        cfg.readTimeoutMs = 120_000L
        cfg.totalDeadlineMs = 150_000L
        val bounded = cfg.ekycClient(
            origin = "https://fixture.modal.run",
            apiKey = "0123456789abcdef0123456789abcdef",
            modalKey = "wk-test",
            modalSecret = "ws-test",
            mapper = JsonMapper.builder().build(),
        )
        assertEquals(false, bounded.faceComparisonEnabled)
        assertEquals(90_000L, bounded.totalDeadlineMs)
        assertEquals(90_000L, bounded.attemptTimeoutMs)

        // readTimeoutMs > totalDeadlineMs must clamp readTimeoutMs & attemptTimeoutMs without inflating totalDeadlineMs.
        val clampedCfg = EkycConfig().apply {
            connectTimeoutMs = 5_000L
            readTimeoutMs = 90_000L
            totalDeadlineMs = 75_000L
        }
        val clampedClient = clampedCfg.ekycClient(
            origin = "https://fixture.modal.run",
            apiKey = "0123456789abcdef0123456789abcdef",
            modalKey = "wk-test",
            modalSecret = "ws-test",
            mapper = JsonMapper.builder().build(),
        )
        assertEquals(75_000L, clampedClient.totalDeadlineMs)
        assertEquals(75_000L, clampedClient.attemptTimeoutMs)

        // Default config: connect=5s + read=65s = attemptTimeout 70s <= totalDeadline 75s.
        val defaultClient = EkycConfig().ekycClient(
            origin = "https://fixture.modal.run",
            apiKey = "0123456789abcdef0123456789abcdef",
            modalKey = "wk-test",
            modalSecret = "ws-test",
            mapper = JsonMapper.builder().build(),
        )
        assertEquals(75_000L, defaultClient.totalDeadlineMs)
        assertEquals(70_000L, defaultClient.attemptTimeoutMs)

        // After 5s elapsed, 5000 + 250 + 70000 = 75250 > 75000 -> retry aborted.
        var clockAbort = 0L
        val b2 = RestClient.builder().baseUrl("https://fixture.modal.run")
        val m2 = MockRestServiceServer.bindTo(b2).build()
        val abortClient = EkycClient(
            upstream = b2.build(),
            mapper = JsonMapper.builder().build(),
            maxRetries = 1,
            totalDeadlineMs = 75_000L,
            attemptTimeoutMs = 70_000L,
            clockNanos = { clockAbort },
        )
        m2.expect(requestTo("https://fixture.modal.run/ekyc/documents/cccd_front/extract"))
            .andRespond {
                clockAbort += 5_000_000_000L
                withStatus(HttpStatus.SERVICE_UNAVAILABLE).createResponse(it)
            }
        val err = assertFailsWith<AppException> {
            abortClient.extract(EkycDocumentSlot.CCCD_FRONT, MediaType.IMAGE_JPEG, byteArrayOf(9, 8, 7).inputStream())
        }
        assertEquals(ErrorCode.EKYC_BUSY, err.errorCode)
        m2.verify()

        // After 4s elapsed, 4000 + 250 + 70000 = 74250 <= 75000 -> retry allowed once.
        var clockRetry = 0L
        val b3 = RestClient.builder().baseUrl("https://fixture.modal.run")
        val m3 = MockRestServiceServer.bindTo(b3).build()
        val allowClient = EkycClient(
            upstream = b3.build(),
            mapper = JsonMapper.builder().build(),
            maxRetries = 1,
            totalDeadlineMs = 75_000L,
            attemptTimeoutMs = 70_000L,
            clockNanos = { clockRetry },
        )
        m3.expect(requestTo("https://fixture.modal.run/ekyc/documents/cccd_front/extract"))
            .andRespond {
                clockRetry += 4_000_000_000L
                withStatus(HttpStatus.SERVICE_UNAVAILABLE).createResponse(it)
            }
        m3.expect(requestTo("https://fixture.modal.run/ekyc/documents/cccd_front/extract"))
            .andRespond(
                withSuccess(
                    """{"success":true,"data":{"slot":"cccd_front","qrStatus":"QR_NOT_FOUND","candidates":[]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val ok = allowClient.extract(EkycDocumentSlot.CCCD_FRONT, MediaType.IMAGE_JPEG, byteArrayOf(3, 2, 1).inputStream())
        assertEquals("cccd_front", ok.path("slot").asString())
        m3.verify()

        // CLASSIFY_ONLY sends ?ocr_mode=classify_only and does NOT share cache with FULL on same image bytes.
        val b4 = RestClient.builder().baseUrl("https://fixture.modal.run")
        val m4 = MockRestServiceServer.bindTo(b4).build()
        val modeClient = EkycClient(b4.build(), JsonMapper.builder().build())
        val sharedBytes = byteArrayOf(11, 22, 33)
        m4.expect(requestTo("https://fixture.modal.run/ekyc/documents/cccd_back/extract?ocr_mode=classify_only"))
            .andRespond(
                withSuccess(
                    """{"success":true,"data":{"slot":"cccd_back","qrStatus":"NOT_FOUND","ocrStatus":"SKIPPED","warnings":[],"candidates":[]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        m4.expect(requestTo("https://fixture.modal.run/ekyc/documents/cccd_back/extract"))
            .andRespond(
                withSuccess(
                    """{"success":true,"data":{"slot":"cccd_back","qrStatus":"NOT_FOUND","ocrStatus":"OCR_READ","warnings":[],"candidates":[]}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val classifyRes = modeClient.extract(EkycDocumentSlot.CCCD_BACK, MediaType.IMAGE_JPEG, sharedBytes.inputStream(), EkycOcrMode.CLASSIFY_ONLY)
        val fullRes = modeClient.extract(EkycDocumentSlot.CCCD_BACK, MediaType.IMAGE_JPEG, sharedBytes.inputStream(), EkycOcrMode.FULL)
        assertEquals("SKIPPED", classifyRes.path("ocrStatus").asString())
        assertEquals("OCR_READ", fullRes.path("ocrStatus").asString())
        m4.verify()
    }

    @Test
    fun `keeps face evaluation off when faceComparisonEnabled is false`() {
        val result = client.evaluateFace(mapOf("selfie" to byteArrayOf(1, 2, 3)), qualityOnly = true)
        assertEquals("NOT_EVALUATED", result.path("mode").asString())
        assertEquals("DISABLED", result.path("reason").asString())
        assertEquals("NOT_EVALUATED", result.path("liveness").asString())
        mock.verify()
    }
}
