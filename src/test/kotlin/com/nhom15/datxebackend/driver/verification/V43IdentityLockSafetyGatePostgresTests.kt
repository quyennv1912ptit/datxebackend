package com.nhom15.datxebackend.driver.verification

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class V43IdentityLockSafetyGatePostgresTests {

    private fun withIsolatedSchema(block: (DriverManagerDataSource, String) -> Unit) {
        val url = System.getenv("TEST_DB_URL")
        val user = System.getenv("TEST_DB_USER")
        val pass = System.getenv("TEST_DB_PASSWORD")
        val schema = "v43gate_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val admin = JdbcTemplate(DriverManagerDataSource(url, user, pass))
        admin.execute("CREATE SCHEMA $schema")
        try {
            val ds = DriverManagerDataSource(url, user, pass).apply {
                connectionProperties = Properties().apply { setProperty("currentSchema", schema) }
            }
            block(ds, schema)
        } finally {
            admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    @Test
    fun `V43 migrates cleanly on fresh PostgreSQL 17 database`() {
        withIsolatedSchema { ds, schema ->
            val result = Flyway.configure()
                .dataSource(ds)
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate()
            assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(43)
        }
    }

    @Test
    fun `V42 to V43 upgrade succeeds with APPROVED plus DRAFT, REJECTED, REVOKED rows and enforces partial UNIQUE indexes under concurrency`() {
        withIsolatedSchema { ds, schema ->
            // 1. Migrate up to V42
            Flyway.configure()
                .dataSource(ds)
                .schemas(schema)
                .defaultSchema(schema)
                .target("42")
                .load()
                .migrate()

            val jdbc = JdbcTemplate(ds)
            // Seed synthetic rows: one APPROVED + DRAFT/REJECTED/REVOKED sharing the same CCCD/GPLX
            insertApp(jdbc, "uid-approved", "APPROVED", "001206000099", "790123456789")
            insertApp(jdbc, "uid-draft", "DRAFT", "001206000099", "790123456789")
            insertApp(jdbc, "uid-rejected", "REJECTED", "001206000099", "790123456789")
            insertApp(jdbc, "uid-revoked", "REVOKED", "001206000099", "790123456789")

            // 2. Upgrade to V43 -> MUST succeed because DRAFT/REJECTED/REVOKED are excluded from partial UNIQUE indexes
            val upgrade = Flyway.configure()
                .dataSource(ds)
                .schemas(schema)
                .defaultSchema(schema)
                .target("43")
                .load()
                .migrate()
            assertThat(upgrade.migrationsExecuted).isEqualTo(1)

            // 3. Partial UNIQUE index blocks duplicate active/approved CCCD & GPLX (including whitespace/case variant)
            assertThatThrownBy {
                insertApp(jdbc, "uid-dup-cccd", "SUBMITTED", "001206000099", "888888000001")
            }.isInstanceOf(DataIntegrityViolationException::class.java)

            assertThatThrownBy {
                insertApp(jdbc, "uid-dup-gplx", "UNDER_REVIEW", "001206000088", " 790123456789 ")
            }.isInstanceOf(DataIntegrityViolationException::class.java)

            // 4. Concurrent 2-thread submissions for the SAME fresh CCCD -> exactly 1 succeeds, 1 fails with unique violation
            val startLatch = CountDownLatch(1)
            val readyLatch = CountDownLatch(2)
            val successes = AtomicInteger(0)
            val uniqueViolations = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val f1 = pool.submit {
                    readyLatch.countDown()
                    startLatch.await()
                    try {
                        insertApp(jdbc, "uid-race-1", "SUBMITTED", "001206999999", "999000111111")
                        successes.incrementAndGet()
                    } catch (_: DataIntegrityViolationException) {
                        uniqueViolations.incrementAndGet()
                    }
                }
                val f2 = pool.submit {
                    readyLatch.countDown()
                    startLatch.await()
                    try {
                        insertApp(jdbc, "uid-race-2", "SUBMITTED", "001206999999", "999000222222")
                        successes.incrementAndGet()
                    } catch (_: DataIntegrityViolationException) {
                        uniqueViolations.incrementAndGet()
                    }
                }
                readyLatch.await()
                startLatch.countDown()
                f1.get()
                f2.get()
            } finally {
                pool.shutdownNow()
            }
            assertThat(successes.get()).isEqualTo(1)
            assertThat(uniqueViolations.get()).isEqualTo(1)
        }
    }

    @Test
    fun `V43 fails fast if pre-existing active duplicate locked_cccd_id rows exist in V42`() {
        withIsolatedSchema { ds, schema ->
            Flyway.configure()
                .dataSource(ds)
                .schemas(schema)
                .defaultSchema(schema)
                .target("42")
                .load()
                .migrate()

            val jdbc = JdbcTemplate(ds)
            insertApp(jdbc, "uid-dup-a", "APPROVED", "001206111111", "777000111111")
            insertApp(jdbc, "uid-dup-b", "APPROVED", "001206111111", "777000222222")

            assertThatThrownBy {
                Flyway.configure()
                    .dataSource(ds)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target("43")
                    .load()
                    .migrate()
            }.hasMessageContaining("uq_driver_verification_apps_locked_cccd")
        }
    }

    private fun insertApp(
        jdbc: JdbcTemplate,
        ownerUid: String,
        status: String,
        lockedCccdId: String,
        verifiedGplx: String,
    ) {
        jdbc.update(
            """INSERT INTO driver_verification_applications
               (id, owner_uid, status, revision, confirmation_current, resubmission_count,
                locked_cccd_id, verified_gplx_number, identity_hold, created_at, updated_at)
               VALUES (?, ?, ?, 1, true, 0, ?, ?, false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
            UUID.randomUUID(),
            ownerUid,
            status,
            lockedCccdId,
            verifiedGplx,
        )
    }
}
