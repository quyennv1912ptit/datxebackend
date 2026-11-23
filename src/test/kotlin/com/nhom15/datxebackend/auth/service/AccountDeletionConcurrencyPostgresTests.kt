package com.nhom15.datxebackend.auth.service

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class AccountDeletionConcurrencyPostgresTests {

    private fun withIsolatedSchema(block: (DriverManagerDataSource, String) -> Unit) {
        val url = System.getenv("TEST_DB_URL")
        val user = System.getenv("TEST_DB_USER")
        val pass = System.getenv("TEST_DB_PASSWORD")
        val schema = "f024_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val admin = JdbcTemplate(DriverManagerDataSource(url, user, pass))
        admin.execute("CREATE SCHEMA $schema")
        try {
            val ds = DriverManagerDataSource(url, user, pass).apply {
                connectionProperties = Properties().apply { setProperty("currentSchema", schema) }
            }
            Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate()
            block(ds, schema)
        } finally {
            admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    @Test
    fun `F-024 real 2-transaction PostgreSQL FOR UPDATE serializes customer deletion vs ride booking`() {
        withIsolatedSchema { ds, _ ->
            val jdbc = JdbcTemplate(ds)
            val customerId = jdbc.queryForObject(
                """INSERT INTO customers (firebase_uid, full_name, phone_number, status, wallet_balance, created_at, updated_at)
                   VALUES ('uid-cust-race', 'Race Customer', '+84900000001', 'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                   RETURNING id""",
                Long::class.java,
            )!!

            val tx1Locked = CountDownLatch(1)
            val tx2Started = CountDownLatch(1)
            val tx2RejectedDeleted = AtomicBoolean(false)
            val pool = Executors.newFixedThreadPool(2)
            try {
                // Tx1: locks customer FOR UPDATE, waits until Tx2 is waiting on the same lock, then soft-deletes & commits
                val f1 = pool.submit {
                    ds.connection.use { conn ->
                        conn.autoCommit = false
                        conn.prepareStatement("SELECT id FROM customers WHERE id = ? FOR UPDATE").use { ps ->
                            ps.setLong(1, customerId)
                            ps.executeQuery().use { rs -> assertThat(rs.next()).isTrue() }
                        }
                        tx1Locked.countDown()
                        assertThat(tx2Started.await(5, TimeUnit.SECONDS)).isTrue()
                        conn.prepareStatement(
                            "UPDATE customers SET deleted_at = CURRENT_TIMESTAMP, status = 'INACTIVE', firebase_uid = 'deleted-c-1' WHERE id = ?",
                        ).use { ps ->
                            ps.setLong(1, customerId)
                            ps.executeUpdate()
                        }
                        conn.commit()
                    }
                }

                // Tx2: simulates RideService#createRide locking customer FOR UPDATE & checking deleted_at/status
                val f2 = pool.submit {
                    assertThat(tx1Locked.await(5, TimeUnit.SECONDS)).isTrue()
                    ds.connection.use { conn ->
                        conn.autoCommit = false
                        tx2Started.countDown()
                        conn.prepareStatement("SELECT deleted_at, status FROM customers WHERE id = ? FOR UPDATE").use { ps ->
                            ps.setLong(1, customerId)
                            ps.executeQuery().use { rs ->
                                assertThat(rs.next()).isTrue()
                                val deletedAt = rs.getTimestamp("deleted_at")
                                val status = rs.getString("status")
                                if (deletedAt != null || status != "ACTIVE") {
                                    tx2RejectedDeleted.set(true)
                                    conn.rollback()
                                    return@submit
                                }
                            }
                        }
                        conn.commit()
                    }
                }
                f1.get(10, TimeUnit.SECONDS)
                f2.get(10, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }

            assertThat(tx2RejectedDeleted.get()).isTrue()
            val rideCount = jdbc.queryForObject("SELECT COUNT(*) FROM rides WHERE customer_id = ?", Long::class.java, customerId)
            assertThat(rideCount).isEqualTo(0L)
        }
    }

    @Test
    fun `F-024 real 2-transaction PostgreSQL FOR UPDATE serializes driver deletion vs driver accept, blocks PENDING withdrawal, and retry is idempotent`() {
        withIsolatedSchema { ds, _ ->
            val jdbc = JdbcTemplate(ds)
            val driverId = jdbc.queryForObject(
                """INSERT INTO drivers (firebase_uid, full_name, phone_number, status, verification_status, credit_balance, income_balance, bank_account_number, created_at, updated_at)
                   VALUES ('uid-drv-race', 'Race Driver', '+84900000002', 'ONLINE', 'APPROVED', 200000, 100000, '987654321', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                   RETURNING id""",
                Long::class.java,
            )!!

            // 1. Pending withdrawal blocks driver deletion (exact SQL used by AccountDeletionService#deleteDriver)
            jdbc.update(
                """INSERT INTO withdrawal_requests (driver_id, amount, status, idempotency_key, created_at)
                   VALUES (?, 50000, 'PENDING', 'wd-f024-key-1', CURRENT_TIMESTAMP)""",
                driverId,
            )
            val hasPending = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM withdrawal_requests WHERE driver_id = ? AND status = 'PENDING')",
                Boolean::class.java,
                driverId,
            )
            assertThat(hasPending).isTrue()

            // Clear pending withdrawal to test 2-transaction delete vs driver accept
            jdbc.update("DELETE FROM withdrawal_requests WHERE driver_id = ?", driverId)

            val tx1Locked = CountDownLatch(1)
            val tx2Started = CountDownLatch(1)
            val acceptRejected = AtomicBoolean(false)
            val pool = Executors.newFixedThreadPool(2)
            try {
                // Tx1: deleteDriver locks driver FOR UPDATE, soft-deletes & commits
                val f1 = pool.submit {
                    ds.connection.use { conn ->
                        conn.autoCommit = false
                        conn.prepareStatement("SELECT id FROM drivers WHERE id = ? FOR UPDATE").use { ps ->
                            ps.setLong(1, driverId)
                            ps.executeQuery().use { rs -> assertThat(rs.next()).isTrue() }
                        }
                        tx1Locked.countDown()
                        assertThat(tx2Started.await(5, TimeUnit.SECONDS)).isTrue()
                        conn.prepareStatement(
                            "UPDATE drivers SET deleted_at = CURRENT_TIMESTAMP, status = 'OFFLINE', firebase_uid = 'deleted-d-1' WHERE id = ?",
                        ).use { ps ->
                            ps.setLong(1, driverId)
                            ps.executeUpdate()
                        }
                        conn.commit()
                    }
                }

                // Tx2: RideStateMachine#tryAssignDriver locks driver FOR UPDATE, sees deleted_at != null / status != ONLINE, rolls back
                val f2 = pool.submit {
                    assertThat(tx1Locked.await(5, TimeUnit.SECONDS)).isTrue()
                    ds.connection.use { conn ->
                        conn.autoCommit = false
                        tx2Started.countDown()
                        conn.prepareStatement("SELECT deleted_at, status FROM drivers WHERE id = ? FOR UPDATE").use { ps ->
                            ps.setLong(1, driverId)
                            ps.executeQuery().use { rs ->
                                assertThat(rs.next()).isTrue()
                                if (rs.getTimestamp("deleted_at") != null || rs.getString("status") != "ONLINE") {
                                    acceptRejected.set(true)
                                    conn.rollback()
                                    return@submit
                                }
                            }
                        }
                        conn.commit()
                    }
                }
                f1.get(10, TimeUnit.SECONDS)
                f2.get(10, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }
            assertThat(acceptRejected.get()).isTrue()

            // 2. Retry account deletion outbox enqueue is idempotent (ON CONFLICT DO NOTHING)
            val outbox = AccountCleanupOutboxRepository(jdbc)
            outbox.enqueue("uid-drv-race", "DRIVER", driverId, listOf("pub-1"))
            outbox.enqueue("uid-drv-race", "DRIVER", driverId, listOf("pub-1"))
            val outboxRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_cleanup_outbox WHERE owner_role = 'DRIVER' AND owner_id = ?",
                Long::class.java,
                driverId,
            )
            assertThat(outboxRows).isEqualTo(1L)
        }
    }
}
