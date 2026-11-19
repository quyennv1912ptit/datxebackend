package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.ekyc.EkycClient
import com.sun.net.httpserver.HttpServer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.Optional
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class VerificationConcurrencyPostgresTest {
    @TempDir
    lateinit var tempDir: Path

    private val schema = "ekyc_rel_" + UUID.randomUUID().toString().replace("-", "")
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private lateinit var adminJdbc: JdbcTemplate
    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var txManager: DataSourceTransactionManager
    private lateinit var driverRepo: DriverRepository
    private lateinit var projection: VerificationProjectionRepository
    private lateinit var storage: DriverVerificationStorageService
    private lateinit var server: HttpServer
    private lateinit var actor: AuthUser
    private var modalHandler = AtomicReference<(String) -> String>({ _ -> defaultExtractionBody("cccd_front") })
    private val modalCallCount = AtomicInteger(0)
    private val modalStatus = AtomicInteger(200)
    private val modalRequests = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun setUp() {
        val url = System.getenv("TEST_DB_URL")
        val user = System.getenv("TEST_DB_USER") ?: "datxe"
        val password = System.getenv("TEST_DB_PASSWORD") ?: "datxe123"
        adminJdbc = JdbcTemplate(DriverManagerDataSource(url, user, password))
        adminJdbc.execute("CREATE SCHEMA $schema")

        val hikari = HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            schema = this@VerificationConcurrencyPostgresTest.schema
            maximumPoolSize = 4
            connectionInitSql = "SET lock_timeout = '3s'"
            dataSourceProperties = Properties().apply {
                setProperty("currentSchema", this@VerificationConcurrencyPostgresTest.schema)
            }
        }
        dataSource = HikariDataSource(hikari)
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .load()
            .migrate()

        jdbc = JdbcTemplate(dataSource)
        txManager = DataSourceTransactionManager(dataSource)
        projection = VerificationProjectionRepository(jdbc, mapper)
        storage = DriverVerificationStorageService(tempDir.toString(), "", false)
        driverRepo = mock(DriverRepository::class.java)
        actor = AuthUser(101L, "synthetic-rel-driver", Role.DRIVER, "Test Driver", null, "+84900000101", "OFFLINE")
        `when`(driverRepo.findByFirebaseUid(actor.firebaseUid)).thenReturn(Optional.empty())

        modalCallCount.set(0)
        modalStatus.set(200)
        modalRequests.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/ekyc/documents/") { exchange ->
            modalCallCount.incrementAndGet()
            val fullPath = exchange.requestURI.path + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
            modalRequests.add(fullPath)
            val slot = exchange.requestURI.path.split("/").dropLast(1).lastOrNull() ?: "cccd_front"
            exchange.requestBody.readAllBytes()
            val status = modalStatus.get()
            val payload = modalHandler.get().invoke(slot).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        runCatching { server.stop(0) }
        runCatching { dataSource.close() }
        runCatching { adminJdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }

    private fun buildService(): DriverVerificationWorkflowService {
        val http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        val factory = JdkClientHttpRequestFactory(http).apply {
            setReadTimeout(Duration.ofSeconds(65))
        }
        val rest = RestClient.builder()
            .requestFactory(factory)
            .baseUrl("http://127.0.0.1:${server.address.port}")
            .build()
        val client = EkycClient(rest, mapper, maxRetries = 0, faceComparisonEnabled = false, totalDeadlineMs = 65_000L)
        return DriverVerificationWorkflowService(
            jdbc = jdbc,
            mapper = mapper,
            storage = storage,
            projection = projection,
            driverRepository = driverRepo,
            ekycClient = client,
            lookupHmacKey = "synthetic-hmac-test-key",
            transactionManager = txManager,
        )
    }

    @Test
    fun `simulated 60s Modal delay releases DB row lock so qr-scan completes immediately and stale OCR fails closed`() {
        val service = buildService()
        val created = service.create(actor)
        val appId = UUID.fromString(created["id"] as String)
        service.replaceCapture(actor, appId, "cccd_front", 0L, "image/png", validPngBytes(64, 40, Color.BLUE))

        val modalStarted = CountDownLatch(1)
        val releaseModal = CountDownLatch(1)
        modalHandler.set { slot ->
            modalStarted.countDown()
            releaseModal.await(60, TimeUnit.SECONDS)
            defaultExtractionBody(slot)
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            val extractFuture = executor.submit<Any?> {
                service.extractSlot(actor, appId, "cccd_front", 1L)
            }
            assertTrue(modalStarted.await(5, TimeUnit.SECONDS), "extractSlot must reach Modal HTTP stage")
            Thread.sleep(50)

            // Verify PostgreSQL holds zero row-exclusive locks while Modal HTTP call is in progress.
            val activeExclusiveLocks = jdbc.queryForObject(
                """SELECT COUNT(*) FROM pg_locks
                   WHERE relation = ?::regclass
                     AND mode = 'RowExclusiveLock'""",
                Long::class.java,
                "$schema.driver_verification_applications",
            ) ?: 0L
            assertEquals(0L, activeExclusiveLocks, "No FOR UPDATE / RowExclusiveLock may be held while Modal runs")

            // POST /qr-scan must succeed immediately without waiting for the slow Modal extraction.
            val qrStartedNanos = System.nanoTime()
            val qrResult = service.submitQrLiveScan(
                actor = actor,
                applicationId = appId,
                body = QrScanBody(
                    revision = 1L,
                    rawPayload = "001099000123|123456789|NGUYEN VAN TEST|01011999|Nam|Ha Noi|01012022",
                    documentSide = "FRONT",
                    documentType = "CCCD",
                ),
            )
            val qrElapsedMs = (System.nanoTime() - qrStartedNanos) / 1_000_000L
            assertTrue(qrElapsedMs < 2500L, "Expected fast unblocked qr-scan (<2500ms) but took ${qrElapsedMs}ms")
            assertEquals(2L, qrResult["revision"])

            releaseModal.countDown()
            val ex = assertFailsWith<Exception> {
                try {
                    extractFuture.get(10, TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
            }
            assertTrue(ex is VerificationWorkflowException && ex.httpStatus == 409 && ex.errorCode == "REVISION_CONFLICT")

            val detail = service.detail(actor, appId)
            assertEquals(2L, detail["revision"])
            @Suppress("UNCHECKED_CAST")
            val doc = detail["document"] as Map<String, Any?>
            assertNotNull(doc["qrLiveScan"], "Valid live QR scan must remain preserved")
        } finally {
            releaseModal.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent extractSlot requests do not double-apply or corrupt state`() {
        val service = buildService()
        val created = service.create(actor)
        val appId = UUID.fromString(created["id"] as String)
        service.replaceCapture(actor, appId, "cccd_front", 0L, "image/png", validPngBytes(64, 40, Color.GREEN))

        val modalStarted = CountDownLatch(1)
        val releaseModal = CountDownLatch(1)
        modalHandler.set { slot ->
            modalStarted.countDown()
            releaseModal.await(10, TimeUnit.SECONDS)
            defaultExtractionBody(slot)
        }

        val executor = Executors.newFixedThreadPool(2)
        try {
            val f1 = executor.submit<Result<Map<String, Any?>>> {
                runCatching { service.extractSlot(actor, appId, "cccd_front", 1L) }
            }
            assertTrue(modalStarted.await(5, TimeUnit.SECONDS))
            val f2 = executor.submit<Result<Map<String, Any?>>> {
                runCatching { service.extractSlot(actor, appId, "cccd_front", 1L) }
            }
            Thread.sleep(100)
            releaseModal.countDown()

            val outcomes = listOf(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS))
            val successes = outcomes.count { it.isSuccess }
            val conflicts = outcomes.mapNotNull { it.exceptionOrNull() as? VerificationWorkflowException }
                .count { it.httpStatus == 409 && it.errorCode == "REVISION_CONFLICT" }
            assertEquals(1, successes)
            assertEquals(1, conflicts)
            assertEquals(2L, service.detail(actor, appId)["revision"])
        } finally {
            releaseModal.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `replacing capture while extractSlot is in flight discards stale OCR result fail closed`() {
        val service = buildService()
        val created = service.create(actor)
        val appId = UUID.fromString(created["id"] as String)
        service.replaceCapture(actor, appId, "cccd_front", 0L, "image/png", validPngBytes(64, 40, Color.RED))

        val modalStarted = CountDownLatch(1)
        val releaseModal = CountDownLatch(1)
        modalHandler.set { slot ->
            modalStarted.countDown()
            releaseModal.await(10, TimeUnit.SECONDS)
            defaultExtractionBody(slot)
        }

        val executor = Executors.newSingleThreadExecutor()
        try {
            val extractFuture = executor.submit<Result<Map<String, Any?>>> {
                runCatching { service.extractSlot(actor, appId, "cccd_front", 1L) }
            }
            assertTrue(modalStarted.await(5, TimeUnit.SECONDS))

            val replaced = service.replaceCapture(actor, appId, "cccd_front", 1L, "image/png", validPngBytes(80, 50, Color.WHITE))
            assertEquals(2L, replaced["revision"])

            releaseModal.countDown()
            val outcome = extractFuture.get(10, TimeUnit.SECONDS)
            val err = outcome.exceptionOrNull() as? VerificationWorkflowException
            assertNotNull(err)
            assertEquals(409, err.httpStatus)
            assertEquals("REVISION_CONFLICT", err.errorCode)

            @Suppress("UNCHECKED_CAST")
            val doc = service.detail(actor, appId)["document"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val proposals = doc["proposals"] as? Map<String, Any?> ?: emptyMap()
            assertNull(proposals["cccd_front"], "Stale OCR proposal must not attach to replacement capture")
        } finally {
            releaseModal.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `repeating extractSlot on unchanged capture reuses persisted proposal without calling Modal again`() {
        val service = buildService()
        val created = service.create(actor)
        val appId = UUID.fromString(created["id"] as String)
        service.replaceCapture(actor, appId, "cccd_front", 0L, "image/png", validPngBytes(64, 40, Color.CYAN))

        val first = service.extractSlot(actor, appId, "cccd_front", 1L)
        assertEquals(2L, first["revision"])
        assertEquals(1, modalCallCount.get())

        val second = service.extractSlot(actor, appId, "cccd_front", 2L)
        assertEquals(2L, second["revision"])
        assertEquals(1, modalCallCount.get(), "Second call must reuse persisted proposal_json")
        assertNotNull(second["proposal"])
    }

    @Test
    fun `qr replay is rejected, ocr mismatch raises QR_OCR_CONFLICT, and identity lock violation blocks activation`() {
        val service = buildService()
        val created = service.create(actor)
        val appId = UUID.fromString(created["id"] as String)

        // Seed identity lock for this account with ID ending 9999
        jdbc.update(
            "UPDATE driver_verification_applications SET locked_cccd_id = ?, locked_full_name = ? WHERE id = ?",
            "046096009999",
            "NGUYỄN VĂN KHÓA",
            appId,
        )

        // Upload cccd_front and extract OCR showing 001095001111
        service.replaceCapture(actor, appId, "cccd_front", 0L, "image/png", validPngBytes(64, 40, Color.WHITE))
        modalHandler.set {
            """{"success":true,"data":{"slot":"cccd_front","qrStatus":"NOT_FOUND","ocrStatus":"OCR_READ","warnings":[],"candidates":[{"fieldKey":"id_number","candidateValue":"001095001111","source":"OCR","sourceDocument":"CCCD","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false}]}}"""
        }
        val afterOcr = service.extractSlot(actor, appId, "cccd_front", 1L)
        assertEquals(2L, afterOcr["revision"])

        // Live QR has 001095002222 -> conflicts with OCR (001095001111) AND violates locked CCCD (046096009999)
        val qrRes = service.submitQrLiveScan(
            actor = actor,
            applicationId = appId,
            body = QrScanBody(
                revision = 2L,
                rawPayload = "001095002222||NGUYỄN VĂN KHÓA|01011995|Nam|HN|01052021",
                documentSide = "FRONT",
                documentType = "CCCD",
            ),
        )
        assertEquals(3L, qrRes["revision"])
        val warnings = (qrRes["verificationWarnings"] as List<*>).filterIsInstance<VerificationWarningDto>()
        val codes = warnings.map { it.code }.toSet()
        assertTrue("QR_OCR_CONFLICT" in codes)
        assertTrue("LOCKED_CCCD_ID_CHANGED" in codes)
        assertTrue(warnings.any { it.code == "LOCKED_CCCD_ID_CHANGED" && it.blocksActivation })

        // Replaying stale revision 2 must fail closed with 409 REVISION_CONFLICT
        val replayErr = assertFailsWith<VerificationWorkflowException> {
            service.submitQrLiveScan(
                actor = actor,
                applicationId = appId,
                body = QrScanBody(
                    revision = 2L,
                    rawPayload = "001095002222||NGUYỄN VĂN KHÓA|01011995|Nam|HN|01052021",
                    documentSide = "FRONT",
                    documentType = "CCCD",
                ),
            )
        }
        assertEquals(409, replayErr.httpStatus)
        assertEquals("REVISION_CONFLICT", replayErr.errorCode)
    }

    @Test
    fun `cccd_back regression matrix preserves DOCUMENT_SLOT_MISMATCH, backend OCR mode policy, and EXTRACTION_REQUIRED guard`() {
        val service = buildService()
        val admin = AuthUser(202L, "synthetic-rel-admin", Role.ADMIN, "Admin", null, null, "ACTIVE")
        val created = service.create(actor)
        val appId = UUID.fromString(created["id"] as String)
        var rev = 0L

        val validFrontResponse = """{"success":true,"data":{"slot":"cccd_front","qrStatus":"QR_FOUND_SUPPORTED","ocrStatus":"OCR_READ","warnings":[],"qr":{"status":"DECODED_VALID","source":"QR_IMAGE","documentSide":"FRONT","cardVariant":"CCCD_2021","fields":{"id_number":"046096003093","full_name":"NGUYỄN VĂN MẪU","dob":"1996-01-01","issue_date":"2021-05-01"},"missingFields":[],"conflictFields":[],"warnings":[],"crossValidationStatus":"MATCHED"},"candidates":[{"fieldKey":"id_number","candidateValue":"046096003093","source":"QR","sourceDocument":"CCCD","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false},{"fieldKey":"full_name","candidateValue":"NGUYỄN VĂN MẪU","source":"OCR","sourceDocument":"CCCD","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false}]}}"""
        val cleanDetails = mapOf(
            "cccd_name" to "NGUYỄN VĂN MẪU",
            "cccd_id" to "046096003093",
            "cccd_dob" to "1996-01-01",
            "cccd_expiry_date" to "2036-01-01",
            "cccd_gender" to "MALE",
            "gplx_number" to "790123456789",
            "gplx_name" to "NGUYỄN VĂN MẪU",
            "gplx_dob" to "1996-01-01",
            "gplx_classes" to listOf("A1"),
            "no_expiry" to true,
            "expiry_date" to null,
        )

        // Regression 6: Incomplete dossier (missing cccd_back capture) fails closed on submit().
        for (slot in listOf("cccd_front", "gplx_front", "gplx_back", "selfie")) {
            val res = service.replaceCapture(actor, appId, slot, rev, "image/png", validPngBytes(64, 40, Color.GRAY))
            rev = (res["revision"] as Number).toLong()
        }
        val missingDetailWarnings = (service.detail(actor, appId)["verificationWarnings"] as List<*>).filterIsInstance<VerificationWarningDto>()
        assertTrue(missingDetailWarnings.any { it.code == "DOCUMENT_REQUIRED" && it.slot == "cccd_back" && it.blocksSubmission })
        rev = (service.confirmFields(actor, appId, rev, cleanDetails)["revision"] as Number).toLong()
        val incompleteErr = assertFailsWith<VerificationWorkflowException> {
            service.submit(actor, appId, "sub-incomplete", rev, "face-comparison-v1", rev)
        }
        assertEquals(400, incompleteErr.httpStatus)
        assertEquals("FIELD_MISSING", incompleteErr.errorCode)

        // Regression 7a: Upload cccd_back, confirm, then deliberately skip extraction -> 400 EXTRACTION_REQUIRED.
        rev = (service.replaceCapture(actor, appId, "cccd_back", rev, "image/png", validPngBytes(64, 40, Color.LIGHT_GRAY))["revision"] as Number).toLong()
        rev = (service.confirmFields(actor, appId, rev, cleanDetails)["revision"] as Number).toLong()
        val skippedErr = assertFailsWith<VerificationWorkflowException> {
            service.submit(actor, appId, "sub-no-extract", rev, "face-comparison-v1", rev)
        }
        assertEquals(400, skippedErr.httpStatus)
        assertEquals("EXTRACTION_REQUIRED", skippedErr.errorCode)

        // Multi-QR conflict response must persist boundToCapture=false and raise CRITICAL QR_OCR_CONFLICT.
        modalHandler.set { slot ->
            when (slot) {
                "cccd_front" -> """{"success":true,"data":{"slot":"cccd_front","qrStatus":"QR_DECODE_FAILED","ocrStatus":"OCR_READ","warnings":["MULTIPLE_DISTINCT_QR_IDENTITIES"],"conflictFields":["id_number","full_name"],"qr":{"status":"CONFLICT","source":"QR_IMAGE","documentSide":"FRONT","fields":{},"missingFields":[],"conflictFields":["id_number","full_name"],"warnings":["MULTIPLE_DISTINCT_QR_IDENTITIES"],"crossValidationStatus":"CONFLICT","boundToCapture":false},"candidates":[{"fieldKey":"id_number","candidateValue":"001095001111","source":"OCR","sourceDocument":"CCCD","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false}]}}"""
                else -> """{"success":true,"data":{"slot":"$slot","qrStatus":"NOT_FOUND","ocrStatus":"OCR_UNSUPPORTED","warnings":[],"candidates":[]}}"""
            }
        }
        val multiQrOut = service.extractSlot(actor, appId, "cccd_front", rev)
        rev = (multiQrOut["revision"] as Number).toLong()
        val frontQr = (multiQrOut["proposal"] as tools.jackson.databind.JsonNode).path("qr")
        assertEquals(false, frontQr.path("boundToCapture").asBoolean())
        val multiWarnings = (multiQrOut["verificationWarnings"] as List<*>).filterIsInstance<VerificationWarningDto>()
        assertTrue(multiWarnings.any { it.code == "QR_OCR_CONFLICT" && it.severity == "CRITICAL" && it.slot == "cccd_front" })

        // Extract valid front, gplx_front, gplx_back.
        rev = (service.replaceCapture(actor, appId, "cccd_front", rev, "image/png", validPngBytes(72, 48, Color.GREEN))["revision"] as Number).toLong()
        modalHandler.set { slot ->
            when (slot) {
                "cccd_front" -> validFrontResponse
                "gplx_front" -> """{"success":true,"data":{"slot":"gplx_front","qrStatus":"NOT_FOUND","ocrStatus":"OCR_READ","warnings":[],"candidates":[{"fieldKey":"id_number","candidateValue":"790123456789","source":"OCR","sourceDocument":"GPLX","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false},{"fieldKey":"full_name","candidateValue":"NGUYỄN VĂN MẪU","source":"OCR","sourceDocument":"GPLX","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false},{"fieldKey":"dob","candidateValue":"1996-01-01","source":"OCR","sourceDocument":"GPLX","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false},{"fieldKey":"vehicle_class","candidateValue":["A1"],"source":"OCR","sourceDocument":"GPLX","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false},{"fieldKey":"expiry","candidateValue":{"no_expiry":true,"expiry_date":null},"source":"OCR","sourceDocument":"GPLX","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false}]}}"""
                "gplx_back" -> """{"success":true,"data":{"slot":"gplx_back","qrStatus":"NOT_FOUND","ocrStatus":"OCR_UNSUPPORTED","warnings":[],"candidates":[]}}"""
                else -> """{"success":true,"data":{"slot":"cccd_back","qrStatus":"NOT_FOUND","ocrStatus":"SKIPPED","warnings":["DOCUMENT_SLOT_MISMATCH"],"candidates":[]}}"""
            }
        }
        rev = (service.extractSlot(actor, appId, "cccd_front", rev)["revision"] as Number).toLong()
        rev = (service.extractSlot(actor, appId, "gplx_front", rev)["revision"] as Number).toLong()
        rev = (service.extractSlot(actor, appId, "gplx_back", rev)["revision"] as Number).toLong()

        // Regression 2: Valid front QR + wrong document type (GPLX) uploaded as cccd_back -> CRITICAL DOCUMENT_SLOT_MISMATCH.
        modalRequests.clear()
        val wrongDocOut = service.extractSlot(actor, appId, "cccd_back", rev)
        rev = (wrongDocOut["revision"] as Number).toLong()
        assertTrue(modalRequests.any { it.endsWith("/cccd_back/extract?ocr_mode=classify_only") })
        val wrongDocWarnings = (wrongDocOut["verificationWarnings"] as List<*>).filterIsInstance<VerificationWarningDto>()
        assertTrue(wrongDocWarnings.any { it.code == "DOCUMENT_SLOT_MISMATCH" && it.severity == "CRITICAL" && it.slot == "cccd_back" })

        // Regression 3: Valid front QR + accidentally re-uploaded cccd_front in cccd_back -> CRITICAL DOCUMENT_SLOT_MISMATCH.
        rev = (service.replaceCapture(actor, appId, "cccd_back", rev, "image/png", validPngBytes(72, 48, Color.ORANGE))["revision"] as Number).toLong()
        modalHandler.set {
            """{"success":true,"data":{"slot":"cccd_back","qrStatus":"QR_FOUND_SUPPORTED","ocrStatus":"OCR_NO_FIELDS","warnings":["DOCUMENT_SLOT_MISMATCH"],"qr":{"status":"DECODED_VALID","source":"QR_IMAGE","documentSide":"BACK","fields":{"id_number":"046096003093","full_name":"NGUYỄN VĂN MẪU","dob":"1996-01-01","issue_date":"2021-05-01"},"missingFields":[],"conflictFields":[],"warnings":[]},"candidates":[]}}"""
        }
        val dupFrontOut = service.extractSlot(actor, appId, "cccd_back", rev)
        rev = (dupFrontOut["revision"] as Number).toLong()
        val dupFrontWarnings = (dupFrontOut["verificationWarnings"] as List<*>).filterIsInstance<VerificationWarningDto>()
        assertTrue(dupFrontWarnings.any { it.code == "DOCUMENT_SLOT_MISMATCH" && it.severity == "CRITICAL" && it.slot == "cccd_back" })

        // Regression 4: cccd_back contains QR belonging to ANOTHER person -> CRITICAL QR_OCR_CONFLICT + BLOCKING LOCKED_CCCD_ID_CHANGED.
        rev = (service.replaceCapture(actor, appId, "cccd_back", rev, "image/png", validPngBytes(72, 48, Color.MAGENTA))["revision"] as Number).toLong()
        modalHandler.set {
            """{"success":true,"data":{"slot":"cccd_back","qrStatus":"QR_FOUND_SUPPORTED","ocrStatus":"SKIPPED","warnings":[],"qr":{"status":"DECODED_VALID","source":"QR_IMAGE","documentSide":"BACK","fields":{"id_number":"099099009999","full_name":"TRẦN NGƯỜI KHÁC","dob":"1990-02-02","issue_date":"2024-08-15"},"missingFields":[],"conflictFields":[],"warnings":[]},"candidates":[{"fieldKey":"issue_date","candidateValue":"2024-08-15","source":"QR","sourceDocument":"CCCD","sourceSide":"BACK","parseStatus":"parsed","validationStatus":"valid","conflict":false}]}}"""
        }
        val otherPersonOut = service.extractSlot(actor, appId, "cccd_back", rev)
        rev = (otherPersonOut["revision"] as Number).toLong()
        val otherWarnings = (otherPersonOut["verificationWarnings"] as List<*>).filterIsInstance<VerificationWarningDto>()
        assertTrue(otherWarnings.any { it.code == "QR_OCR_CONFLICT" && it.severity == "CRITICAL" && it.slot == "cccd_back" })

        // Regression 5: Replace cccd_back mid-extraction -> concurrent replace succeeds immediately, stalled extraction fails 409.
        val stalledStart = CountDownLatch(1)
        val releaseStall = CountDownLatch(1)
        modalHandler.set {
            stalledStart.countDown()
            releaseStall.await(10, TimeUnit.SECONDS)
            """{"success":true,"data":{"slot":"cccd_back","qrStatus":"NOT_FOUND","ocrStatus":"SKIPPED","warnings":[],"candidates":[]}}"""
        }
        rev = (service.replaceCapture(actor, appId, "cccd_back", rev, "image/png", validPngBytes(72, 48, Color.PINK))["revision"] as Number).toLong()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stalledRev = rev
            val stalledFuture = executor.submit<Result<Map<String, Any?>>> {
                runCatching { service.extractSlot(actor, appId, "cccd_back", stalledRev) }
            }
            assertTrue(stalledStart.await(5, TimeUnit.SECONDS))
            val replacedLive = service.replaceCapture(actor, appId, "cccd_back", rev, "image/png", validPngBytes(72, 48, Color.BLUE))
            rev = (replacedLive["revision"] as Number).toLong()
            releaseStall.countDown()
            val stalledErr = stalledFuture.get(10, TimeUnit.SECONDS).exceptionOrNull() as? VerificationWorkflowException
            assertNotNull(stalledErr)
            assertEquals(409, stalledErr.httpStatus)
            assertEquals("REVISION_CONFLICT", stalledErr.errorCode)
        } finally {
            releaseStall.countDown()
            executor.shutdownNow()
        }

        // Regression 7b: Genuine transient Modal 503 failure records EXTRACT_FAILED so manual fallback submit() stays allowed.
        rev = (service.confirmFields(actor, appId, rev, cleanDetails)["revision"] as Number).toLong()
        modalStatus.set(503)
        val outageErr = assertFailsWith<VerificationWorkflowException> {
            service.extractSlot(actor, appId, "cccd_back", rev)
        }
        assertEquals(503, outageErr.httpStatus)
        modalStatus.set(200)
        val fallbackSubmitted = service.submit(actor, appId, "sub-manual-fallback", rev, "face-comparison-v1", rev)
        rev = (fallbackSubmitted["revision"] as Number).toLong()
        assertEquals("SUBMITTED", fallbackSubmitted["status"])

        // Regression 8 + Regression 1: Admin requests NEEDS_MORE_INFO recapture of cccd_back -> must use ocr_mode=full (never classify_only).
        rev = (service.decision(admin, appId, VerificationDecisionRequest(rev, "UNDER_REVIEW"))["revision"] as Number).toLong()
        rev = (service.decision(
            admin,
            appId,
            VerificationDecisionRequest(rev, "NEEDS_MORE_INFO", reason = "Mặt sau chưa rõ", captures = listOf("cccd_back")),
        )["revision"] as Number).toLong()
        rev = (service.replaceCapture(actor, appId, "cccd_back", rev, "image/png", validPngBytes(76, 52, Color.CYAN))["revision"] as Number).toLong()
        rev = (service.confirmFields(actor, appId, rev, cleanDetails)["revision"] as Number).toLong()

        // Trying to resubmit without extracting newly re-uploaded cccd_back must fail 400 EXTRACTION_REQUIRED.
        val resubSkipErr = assertFailsWith<VerificationWorkflowException> {
            service.submit(actor, appId, "sub-resub-skip", rev, "face-comparison-v1", rev)
        }
        assertEquals(400, resubSkipErr.httpStatus)
        assertEquals("EXTRACTION_REQUIRED", resubSkipErr.errorCode)

        // Calling extractSlot("cccd_back") under NEEDS_MORE_INFO targeting cccd_back forces FULL mode!
        modalRequests.clear()
        modalHandler.set {
            """{"success":true,"data":{"slot":"cccd_back","qrStatus":"NOT_FOUND","ocrStatus":"OCR_READ","warnings":[],"candidates":[{"fieldKey":"issue_date","candidateValue":"2021-05-01","source":"OCR","sourceDocument":"CCCD","sourceSide":"BACK","parseStatus":"parsed","validationStatus":"valid","conflict":false}]}}"""
        }
        val fullBackOut = service.extractSlot(actor, appId, "cccd_back", rev)
        rev = (fullBackOut["revision"] as Number).toLong()
        assertTrue(modalRequests.contains("/ekyc/documents/cccd_back/extract"), "Admin-targeted cccd_back must send full mode, not classify_only")
        assertTrue(modalRequests.none { it.contains("classify_only") })
        val resubmitted = service.submit(actor, appId, "sub-resub-ok", rev, "face-comparison-v1", rev)
        rev = (resubmitted["revision"] as Number).toLong()
        assertEquals("SUBMITTED", resubmitted["status"])

        // Final Regression 1 check: Clean first-time dossier -> backend chooses ?ocr_mode=classify_only -> OCR_SKIPPED/SKIPPED -> SUBMITTED.
        val actorClean = AuthUser(303L, "synthetic-clean-driver", Role.DRIVER, "Clean Driver", null, "+84900000303", "OFFLINE")
        val cleanAppId = UUID.fromString(service.create(actorClean)["id"] as String)
        var cleanRev = 0L
        for (slot in DriverVerificationWarnings.REQUIRED_SLOTS) {
            cleanRev = (service.replaceCapture(actorClean, cleanAppId, slot, cleanRev, "image/png", validPngBytes(72, 48, Color.GREEN))["revision"] as Number).toLong()
        }
        modalHandler.set { slot ->
            when (slot) {
                "cccd_front" -> validFrontResponse
                "cccd_back" -> """{"success":true,"data":{"slot":"cccd_back","qrStatus":"NOT_FOUND","ocrStatus":"SKIPPED","warnings":[],"candidates":[]}}"""
                "gplx_front" -> """{"success":true,"data":{"slot":"gplx_front","qrStatus":"NOT_FOUND","ocrStatus":"OCR_READ","warnings":[],"candidates":[{"fieldKey":"id_number","candidateValue":"790123456789","source":"OCR","sourceDocument":"GPLX","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false},{"fieldKey":"full_name","candidateValue":"NGUYỄN VĂN MẪU","source":"OCR","sourceDocument":"GPLX","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false},{"fieldKey":"dob","candidateValue":"1996-01-01","source":"OCR","sourceDocument":"GPLX","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false},{"fieldKey":"vehicle_class","candidateValue":["A1"],"source":"OCR","sourceDocument":"GPLX","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false},{"fieldKey":"expiry","candidateValue":{"no_expiry":true,"expiry_date":null},"source":"OCR","sourceDocument":"GPLX","sourceSide":"FRONT","parseStatus":"parsed","validationStatus":"valid","conflict":false}]}}"""
                else -> """{"success":true,"data":{"slot":"$slot","qrStatus":"NOT_FOUND","ocrStatus":"OCR_UNSUPPORTED","warnings":[],"candidates":[]}}"""
            }
        }
        for (slot in listOf("cccd_front", "cccd_back", "gplx_front", "gplx_back")) {
            cleanRev = (service.extractSlot(actorClean, cleanAppId, slot, cleanRev)["revision"] as Number).toLong()
        }
        assertTrue(modalRequests.any { it.endsWith("/cccd_back/extract?ocr_mode=classify_only") })
        cleanRev = (service.confirmFields(actorClean, cleanAppId, cleanRev, cleanDetails)["revision"] as Number).toLong()
        val cleanSubmit = service.submit(actorClean, cleanAppId, "sub-clean-1", cleanRev, "face-comparison-v1", cleanRev)
        assertEquals("SUBMITTED", cleanSubmit["status"])
        val finalWarnings = (service.detail(actorClean, cleanAppId)["verificationWarnings"] as List<*>).filterIsInstance<VerificationWarningDto>()
        assertTrue(finalWarnings.none { it.code in setOf("DOCUMENT_SLOT_MISMATCH", "OCR_INCOMPLETE") && it.slot == "cccd_back" })
    }

    private fun defaultExtractionBody(slot: String): String =
        """{"success":true,"data":{"slot":"$slot","qrStatus":"NOT_FOUND","ocrStatus":"PARSED","warnings":[],"candidates":[]}}"""

    private fun validPngBytes(width: Int, height: Int, color: Color): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = color
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(img, "png", out)
            out.toByteArray()
        }
    }
}
