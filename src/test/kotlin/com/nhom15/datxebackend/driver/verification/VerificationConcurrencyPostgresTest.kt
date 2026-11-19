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
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/ekyc/documents/") { exchange ->
            modalCallCount.incrementAndGet()
            val slot = exchange.requestURI.path.split("/").dropLast(1).lastOrNull() ?: "cccd_front"
            exchange.requestBody.readAllBytes()
            val payload = modalHandler.get().invoke(slot).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, payload.size.toLong())
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
