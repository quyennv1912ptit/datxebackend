package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.ekyc.EkycClient
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.LinkedHashMap
import java.util.Properties
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class VerificationFacePersistencePostgresTest {
    @TempDir
    lateinit var tempDir: Path

    private val schema = "ekyc_face_" + UUID.randomUUID().toString().replace("-", "")
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private lateinit var adminJdbc: JdbcTemplate
    private lateinit var dataSource: HikariDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var storage: DriverVerificationStorageService
    private lateinit var projection: VerificationProjectionRepository
    private lateinit var drivers: DriverRepository
    private lateinit var ekyc: EkycClient
    private lateinit var service: DriverVerificationWorkflowService

    private val admin = AuthUser(99L, "uid-face-admin", Role.ADMIN, "Admin", null, null, "OFFLINE")
    private val owner = "uid-face-driver-1"
    private val driver = AuthUser(7L, owner, Role.DRIVER, "Driver", null, "+84900000007", "OFFLINE")

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
            schema = this@VerificationFacePersistencePostgresTest.schema
            maximumPoolSize = 3
            connectionInitSql = "SET lock_timeout = '3s'"
            dataSourceProperties = Properties().apply {
                setProperty("currentSchema", this@VerificationFacePersistencePostgresTest.schema)
            }
        }
        dataSource = HikariDataSource(hikari)
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate()
        jdbc = JdbcTemplate(dataSource)
        storage = mock(DriverVerificationStorageService::class.java)
        projection = mock(VerificationProjectionRepository::class.java)
        drivers = mock(DriverRepository::class.java)
        ekyc = mock(EkycClient::class.java)
        service = DriverVerificationWorkflowService(
            jdbc, mapper, storage, projection, drivers,
            ekycClient = ekyc,
            transactionManager = DataSourceTransactionManager(dataSource),
        )
    }

    @AfterEach
    fun tearDown() {
        runCatching { dataSource.close() }
        runCatching { adminJdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }

    private fun readSnapshot(applicationId: UUID, revision: Long): Map<String, Any?> {
        val raw = jdbc.queryForObject(
            "SELECT snapshot_json FROM driver_verification_submissions WHERE application_id = ? AND revision = ?",
            String::class.java,
            applicationId,
            revision,
        )!!
        val type = mapper.typeFactory.constructMapType(LinkedHashMap::class.java, String::class.java, Any::class.java)
        return mapper.readValue(raw, type)
    }

    private fun faceResult(): tools.jackson.databind.JsonNode {
        val result = mapper.createObjectNode()
        result.put("mode", "COMPARED")
        result.put("liveness", "LIVE")
        val pairs = mapper.createArrayNode()
        val statuses = listOf("MATCH", "POSSIBLE_MISMATCH", "MATCH")
        for ((index, pair) in DriverVerificationWarnings.FACE_EXPECTED_PAIRS.withIndex()) {
            val item = mapper.createObjectNode()
            item.put("left", pair.first)
            item.put("right", pair.second)
            item.put("status", statuses[index])
            pairs.add(item)
        }
        result.set("pairs", pairs)
        val quality = mapper.createObjectNode()
        quality.put("selfie", "FACE_BLURRY")
        result.set("quality", quality)
        return result
    }

    @Test
    fun `face comparison persists normalized signals that block approval and surface in review queue`() {
        val appId = UUID.randomUUID()
        val confirmed = mapOf(
            "cccd_name" to "NGUYEN VAN A",
            "cccd_id" to "999206000001",
            "cccd_dob" to "2000-01-01",
            "gplx_number" to "999999000001",
            "gplx_name" to "NGUYEN VAN A",
            "gplx_dob" to "2000-01-01",
            "gplx_classes" to listOf("A1"),
            "no_expiry" to true,
        )
        jdbc.update(
            """INSERT INTO driver_verification_applications
               (id, owner_uid, status, revision, submission_revision, submitted_at, face_consent, confirmed_fields, confirmation_current)
               VALUES (?, ?, 'UNDER_REVIEW', 2, 2, ?, ?, ?, true)""",
            appId,
            owner,
            Timestamp.from(Instant.now()),
            mapper.writeValueAsString(mapOf("version" to "face-comparison-v1")),
            mapper.writeValueAsString(confirmed),
        )
        val captureIds = mapOf(
            "cccd_front" to UUID.randomUUID(),
            "gplx_front" to UUID.randomUUID(),
            "selfie" to UUID.randomUUID(),
        )
        val captures = captureIds.mapValues { (_, id) ->
            mapOf("id" to id.toString(), "quality" to mapOf("warnings" to emptyList<String>()))
        }
        jdbc.update(
            """INSERT INTO driver_verification_submissions
               (application_id, revision, submitted_at, snapshot_json) VALUES (?, 2, ?, ?)""",
            appId,
            Timestamp.from(Instant.now()),
            mapper.writeValueAsString(
                linkedMapOf<String, Any?>(
                    "captures" to captures,
                    "confirmed" to confirmed,
                    "revision" to 2L,
                    "faceConsent" to mapOf("version" to "face-comparison-v1"),
                    "warningCount" to 1,
                    "affectedWarnings" to 4,
                ),
            ),
        )
        for ((slot, id) in captureIds) {
            jdbc.update(
                """INSERT INTO driver_verification_captures
                   (id, application_id, slot, storage_key, mime_type, byte_size, sha256, quality_json, captured_revision)
                   VALUES (?, ?, ?, ?, 'image/png', 3, 'hash', '{"warnings":[]}', 1)""",
                id,
                appId,
                slot,
                "key-$slot",
            )
        }
        `when`(storage.read(anyString(), anyInt(), anyString())).thenReturn(byteArrayOf(1, 2, 3))
        `when`(ekyc.faceComparisonEnabled).thenReturn(true)
        val result = faceResult()
        `when`(ekyc.evaluateFace(anyMap(), anyBoolean())).thenReturn(result)

        assertEquals("REVISION_CONFLICT", assertFailsWith<VerificationWorkflowException> {
            service.faceComparison(admin, appId, 2L, 99L)
        }.errorCode)
        assertEquals(result, service.faceComparison(admin, appId, 2L, 2L))

        val updated = readSnapshot(appId, 2L)
        assertEquals(listOf("MATCH", "POSSIBLE_MISMATCH"), updated["faceStatuses"])
        assertEquals(mapOf("selfie" to "FACE_BLURRY"), updated["faceQuality"])

        val warnings = service.detail(admin, appId, review = true)["verificationWarnings"] as List<*>
        assertTrue(warnings.any {
            it is VerificationWarningDto && it.code == "FACE_POSSIBLE_MISMATCH" && it.blocksActivation
        })
        assertEquals("VERIFICATION_ACTIVATION_BLOCKED", assertFailsWith<VerificationWorkflowException> {
            service.decision(admin, appId, VerificationDecisionRequest(2L, "APPROVED"))
        }.errorCode)

        val queue = service.reviewQueue(admin, null, appId, 10, 0)
        @Suppress("UNCHECKED_CAST")
        val item = (queue["items"] as List<Map<String, Any?>>).single()
        assertEquals(1, item["warningCount"])
        assertEquals(4, item["affectedWarnings"])
    }

    @Test
    fun `cancel reverify removes only retired draft capture files after commit`() {
        val appId = UUID.randomUUID()
        val approvedId = UUID.randomUUID()
        val draftId = UUID.randomUUID()
        val confirmed = mapOf("cccd_name" to "NGUYEN VAN A", "cccd_id" to "999206000001")
        jdbc.update(
            """INSERT INTO driver_verification_applications
               (id, owner_uid, status, revision, submission_revision, approved_revision, approved_submission_revision, confirmed_fields, confirmation_current)
               VALUES (?, ?, 'DRAFT', 5, 4, 4, 4, ?, false)""",
            appId,
            owner,
            mapper.writeValueAsString(confirmed),
        )
        jdbc.update(
            """INSERT INTO driver_verification_submissions
               (application_id, revision, submitted_at, snapshot_json) VALUES (?, 4, ?, ?)""",
            appId,
            Timestamp.from(Instant.now()),
            mapper.writeValueAsString(
                linkedMapOf<String, Any?>(
                    "captures" to mapOf("selfie" to mapOf("id" to approvedId.toString())),
                    "confirmed" to confirmed,
                    "revision" to 4L,
                ),
            ),
        )
        for ((id, slot, key) in listOf(
            Triple(approvedId, "selfie", "approved-key"),
            Triple(draftId, "cccd_front", "draft-key"),
        )) {
            jdbc.update(
                """INSERT INTO driver_verification_captures
                   (id, application_id, slot, storage_key, mime_type, byte_size, sha256, quality_json, captured_revision)
                   VALUES (?, ?, ?, ?, 'image/png', 3, 'hash', '{"warnings":[]}', 1)""",
                id,
                appId,
                slot,
                key,
            )
        }

        val response = service.cancelReverify(driver, appId, 5L)
        assertEquals("APPROVED", response["status"])
        verify(storage).deleteQuietly("draft-key")
        verify(storage, never()).deleteQuietly("approved-key")
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM driver_verification_captures WHERE application_id = ? AND retired_at IS NULL",
                Long::class.java,
                appId,
            ),
        )
    }
}
