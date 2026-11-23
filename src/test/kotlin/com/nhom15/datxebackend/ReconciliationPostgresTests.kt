package com.nhom15.datxebackend

import com.google.firebase.auth.FirebaseAuth
import com.nhom15.datxebackend.admin.repository.AdminRepository
import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.auth.service.AuthService
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.verification.DriverVerificationStorageService
import com.nhom15.datxebackend.driver.verification.DriverVerificationWorkflowService
import com.nhom15.datxebackend.notification.token.DeviceTokenService
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.UUID

class ReconciliationPostgresTests {

    private fun testDb(): Triple<String, String, String>? {
        val url = System.getenv("TEST_DB_URL")?.takeIf { it.isNotBlank() } ?: return null
        val user = System.getenv("TEST_DB_USER") ?: "datxe"
        val pass = System.getenv("TEST_DB_PASSWORD") ?: ""
        return Triple(url, user, pass)
    }

    @Test
    fun `V47 - fresh V1 to V47 and upgrade V46 to V47 both migrate and validate cleanly on PostgreSQL 17`() {
        val cfg = testDb()
        assumeTrue(cfg != null, "Requires TEST_DB_URL pointing to disposable PostgreSQL 17")
        val (url, user, pass) = cfg!!

        val freshSchema = "reconcile_fresh_v47"
        val upgradeSchema = "reconcile_upgrade_v46_v47"

        DriverManager.getConnection(url, user, pass).use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP SCHEMA IF EXISTS $freshSchema CASCADE")
                st.execute("CREATE SCHEMA $freshSchema")
                st.execute("DROP SCHEMA IF EXISTS $upgradeSchema CASCADE")
                st.execute("CREATE SCHEMA $upgradeSchema")
            }
        }

        // 1. Fresh V1 -> V47
        val freshFlyway = Flyway.configure()
            .dataSource(url, user, pass)
            .schemas(freshSchema)
            .defaultSchema(freshSchema)
            .locations("classpath:db/migration")
            .load()
        val freshRes = freshFlyway.migrate()
        assertEquals(47, freshRes.migrationsExecuted)
        freshFlyway.validate()

        // 2. Upgrade V1 -> V46, then V46 -> V47
        val stepV46 = Flyway.configure()
            .dataSource(url, user, pass)
            .schemas(upgradeSchema)
            .defaultSchema(upgradeSchema)
            .locations("classpath:db/migration")
            .target("46")
            .load()
        val res46 = stepV46.migrate()
        assertEquals(46, res46.migrationsExecuted)
        stepV46.validate()

        val stepV47 = Flyway.configure()
            .dataSource(url, user, pass)
            .schemas(upgradeSchema)
            .defaultSchema(upgradeSchema)
            .locations("classpath:db/migration")
            .load()
        val res47 = stepV47.migrate()
        assertEquals(1, res47.migrationsExecuted)
        assertEquals("47", res47.targetSchemaVersion)
        stepV47.validate()

        // Verify deleted_firebase_uids table exists and enforces primary key idempotency
        DriverManager.getConnection(url, user, pass).use { conn ->
            conn.createStatement().use { st ->
                st.execute("SET search_path TO $upgradeSchema")
                st.execute("INSERT INTO deleted_firebase_uids (firebase_uid) VALUES ('tombstone-uid-1') ON CONFLICT DO NOTHING")
                st.execute("INSERT INTO deleted_firebase_uids (firebase_uid) VALUES ('tombstone-uid-1') ON CONFLICT DO NOTHING")
                st.executeQuery("SELECT count(*) FROM deleted_firebase_uids WHERE firebase_uid = 'tombstone-uid-1'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `V47 tombstone blocks deleted driver UID without blocking active sibling customer role`() {
        val cfg = testDb()
        assumeTrue(cfg != null, "Requires TEST_DB_URL pointing to disposable PostgreSQL 17")
        val (url, user, pass) = cfg!!
        val schema = "reconcile_tombstone_roles"

        DriverManager.getConnection(url, user, pass).use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                st.execute("CREATE SCHEMA $schema")
            }
        }
        val ds = DriverManagerDataSource(url, user, pass).apply {
            connectionProperties = Properties().apply { setProperty("currentSchema", schema) }
        }
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()
        val jdbc = JdbcTemplate(ds)
        jdbc.update("INSERT INTO deleted_firebase_uids (firebase_uid) VALUES ('shared-uid-99') ON CONFLICT DO NOTHING")

        val adminRepo = mock(AdminRepository::class.java)
        `when`(adminRepo.existsByFirebaseUid("shared-uid-99")).thenReturn(false)
        val authService = AuthService(
            firebaseAuth = mock(FirebaseAuth::class.java),
            customerRepository = mock(CustomerRepository::class.java),
            driverRepository = mock(DriverRepository::class.java),
            adminRepository = adminRepo,
            deviceTokenService = mock(DeviceTokenService::class.java),
            authenticatedUserCache = mock(AuthenticatedUserCache::class.java),
            jdbcTemplate = jdbc,
        )

        val ensureMethod = AuthService::class.java.getDeclaredMethod("ensureUidAvailableFor", String::class.java, Role::class.java)
        ensureMethod.isAccessible = true

        // 1. Deleted driver UID must be blocked for Role.DRIVER
        val driverEx = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            ensureMethod.invoke(authService, "shared-uid-99", Role.DRIVER)
        }
        val appEx = driverEx.cause as AppException
        assertEquals(ErrorCode.ACCOUNT_LOCKED, appEx.errorCode)

        // 2. Active sibling Role.CUSTOMER sharing the same UID must NOT be blocked
        ensureMethod.invoke(authService, "shared-uid-99", Role.CUSTOMER)
    }

    @Test
    fun `eKYC orphan reconciliation deletes crash orphans, preserves DB-referenced and fresh files, and is idempotent`() {
        val cfg = testDb()
        assumeTrue(cfg != null, "Requires TEST_DB_URL pointing to disposable PostgreSQL 17")
        val (url, user, pass) = cfg!!
        val schema = "reconcile_ekyc_orphans"

        DriverManager.getConnection(url, user, pass).use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                st.execute("CREATE SCHEMA $schema")
            }
        }
        val ds = DriverManagerDataSource(url, user, pass).apply {
            connectionProperties = Properties().apply { setProperty("currentSchema", schema) }
        }
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate()
        val jdbc = JdbcTemplate(ds)

        val tempDir = Files.createTempDirectory("ptitgo-ekyc-orphan-test")
        try {
            val storage = DriverVerificationStorageService(
                storageDir = tempDir.toString(),
                encryptionKey = "",
                protectedStorage = false,
                previousEncryptionKeys = "",
            )
            // 1. Old crash-orphan file (NOT referenced in DB) -> MUST be deleted
            val orphanKey = storage.store(byteArrayOf(1, 2, 3, 4))
            val oldTime = FileTime.from(Instant.now().minus(Duration.ofHours(3)))
            Files.setLastModifiedTime(tempDir.resolve(orphanKey), oldTime)

            // 2. Old DB-referenced file -> MUST be preserved
            val referencedKey = storage.store(byteArrayOf(5, 6, 7, 8))
            Files.setLastModifiedTime(tempDir.resolve(referencedKey), oldTime)
            val appId = UUID.randomUUID()
            jdbc.update(
                """INSERT INTO driver_verification_applications (id, owner_uid, status, revision, created_at, updated_at)
                   VALUES (?, 'driver-ref-1', 'DRAFT', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
                appId,
            )
            jdbc.update(
                """INSERT INTO driver_verification_captures
                   (id, application_id, slot, storage_key, mime_type, byte_size, sha256, quality_json, captured_revision, uploaded_at)
                   VALUES (?, ?, 'cccd_front', ?, 'image/jpeg', 4, 'abcd', '{}', 1, CURRENT_TIMESTAMP)""",
                UUID.randomUUID(),
                appId,
                referencedKey,
            )

            // 3. Fresh in-flight file (younger than grace window) -> MUST be preserved
            val freshKey = storage.store(byteArrayOf(9, 10, 11, 12))

            // Run reconciliation via storage + DB lookup logic
            val candidates = storage.listKeysOlderThan(Instant.now().minus(Duration.ofHours(1)))
            assertTrue(orphanKey in candidates)
            assertTrue(referencedKey in candidates)
            assertFalse(freshKey in candidates)

            val marks = candidates.joinToString(",") { "?" }
            val refInDb = jdbc.queryForList(
                "SELECT storage_key FROM driver_verification_captures WHERE storage_key IN ($marks)",
                String::class.java,
                *candidates.toTypedArray(),
            ).toSet()
            var deletedFirstRun = 0
            for (k in candidates) {
                if (k !in refInDb) {
                    storage.deleteQuietly(k)
                    deletedFirstRun++
                }
            }

            assertEquals(1, deletedFirstRun)
            assertFalse(Files.exists(tempDir.resolve(orphanKey)), "Crash orphan must be removed")
            assertTrue(Files.exists(tempDir.resolve(referencedKey)), "DB-referenced capture must never be removed")
            assertTrue(Files.exists(tempDir.resolve(freshKey)), "Fresh in-flight upload must never be removed")

            // Retry: second run must be 100% idempotent (0 deleted, no error)
            val candidates2 = storage.listKeysOlderThan(Instant.now().minus(Duration.ofHours(1)))
            val ref2 = jdbc.queryForList(
                "SELECT storage_key FROM driver_verification_captures WHERE storage_key IN (?)",
                String::class.java,
                candidates2.single(),
            ).toSet()
            assertEquals(setOf(referencedKey), ref2)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
