package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.admin.repository.AdminDriverQueryRepository
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import java.util.Optional
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@EnabledIfEnvironmentVariable(named = "VERIFICATION_SYNC_TEST_DB", matches = ".+")
class VerificationProjectionPostgresTest {
    @Test
    fun `checkpoint retry replay and stale revisions remain fail closed`() {
        val source = DriverManagerDataSource(
            System.getenv("VERIFICATION_SYNC_TEST_DB"), "sync_test", System.getenv("VERIFICATION_SYNC_TEST_PASSWORD"),
        )
        Flyway.configure().dataSource(source).load().migrate()
        val jdbc = JdbcTemplate(source)
        val repo = VerificationProjectionRepository(jdbc, JsonMapper.builder().findAndAddModules().build())
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
        val stream = UUID.randomUUID()
        val app = UUID.randomUUID()
        val owner = "synthetic-verification-projection"
        val driverId = jdbc.queryForObject(
            "INSERT INTO drivers(firebase_uid,phone_number,verification_status) VALUES (?,?,'APPROVED') RETURNING id",
            Long::class.java, owner, "0000000000",
        )!!
        val adminQuery = AdminDriverQueryRepository(jdbc, syncEnabled = true)
        fun event(seq: Long, revision: Long, status: VerificationStatus) =
            VerificationEvent(seq, app, revision, owner, status,
                listOf(LicenseEntitlement("A1", LicenseValidity.NO_EXPIRY, null)))
        fun apply(start: Long, feed: VerificationFeed) { tx.executeWithoutResult { repo.applyPage(start, feed) } }

        val expiry = LocalDate.of(2030, 1, 1)
        val approved = event(1, 4, VerificationStatus.APPROVED).copy(cccdExpiresOn = expiry)
        apply(0, VerificationFeed(stream, 2, listOf(approved), true))
        assertNull(repo.current(owner))
        assertEquals("PENDING", adminQuery.findById(driverId)?.verificationStatus)
        val revoked = event(2, 6, VerificationStatus.REVOKED)
        apply(1, VerificationFeed(stream, 2, listOf(revoked), false))
        assertEquals(VerificationStatus.REVOKED, repo.current(owner)?.status)
        assertEquals("REJECTED", adminQuery.findById(driverId)?.verificationStatus)
        apply(1, VerificationFeed(stream, 2, listOf(revoked), false))
        apply(2, VerificationFeed(stream, 3, listOf(event(3, 5, VerificationStatus.APPROVED)), false))
        assertEquals(VerificationStatus.REVOKED, repo.current(owner)?.status)
        assertFailsWith<IllegalArgumentException> { apply(3, VerificationFeed(UUID.randomUUID(), 3, emptyList(), false)) }
        repo.failed()
        assertNull(repo.current(owner))
        assertEquals(3, repo.cursor())
        apply(3, VerificationFeed(stream, 4, listOf(event(4, 8, VerificationStatus.APPROVED).copy(cccdExpiresOn = expiry)), false))
        assertNotNull(repo.synchronizedHead())
        assertEquals(VerificationStatus.APPROVED, repo.current(owner)?.status)
        assertEquals(expiry, repo.current(owner)?.cccdExpiresOn)
        assertEquals("APPROVED", adminQuery.findById(driverId)?.verificationStatus)

        val b = LicenseEntitlement("B", LicenseValidity.HAS_EXPIRY, expiry)
        val grant = event(5, 10, VerificationStatus.APPROVED).copy(
            entitlements = listOf(b),
            capabilityReview = CapabilityReview(LicenseEligibilityPolicy.VERSION, 9,
                LocalDate.of(2025, 1, 1), LicenseRestriction.VERIFIED_NONE,
                selectedGroupIds = listOf("car-auto", "moto-175-plus")),
        )
        apply(4, VerificationFeed(stream, 5, listOf(grant), false))
        val current = assertNotNull(repo.current(owner))
        assertEquals(listOf("car-auto"), current.capabilities.map { it.groupId })
        assertEquals(emptySet(), current.enabledGroups)

        val driver = Driver().apply { id = driverId; firebaseUid = owner }
        val actor = AuthUser(driverId, owner, Role.DRIVER, null, null, null, "OFFLINE")
        val drivers = mock(DriverRepository::class.java)
        val eligibility = mock(DriverVerificationEligibility::class.java)
        `when`(eligibility.currentAtSource()).thenReturn(true)
        `when`(drivers.findByIdForUpdate(driverId)).thenAnswer {
            jdbc.queryForObject("SELECT id FROM drivers WHERE id=? FOR UPDATE", Long::class.java, driverId)
            Optional.of(driver)
        }
        val service = DriverCapabilityService(drivers, repo, eligibility)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) {
                pool.submit<Boolean> {
                    try {
                        tx.execute {
                            service.update(actor, "car-auto", CapabilityPreferenceRequest(10, 0, true))
                        }
                        true
                    } catch (_: ConflictException) {
                        false
                    }
                }
            }
            assertEquals(listOf(false, true), attempts.map { it.get(8, TimeUnit.SECONDS) }.sorted())
        } finally {
            pool.shutdownNow()
        }
        assertEquals(setOf("car-auto"), repo.current(owner)?.enabledGroups)
        assertEquals(setOf("car-auto"), repo.forApplication(app)?.enabledGroups)
        tx.execute { service.update(actor, "car-auto", CapabilityPreferenceRequest(10, 1, false)) }
        assertEquals(emptySet(), repo.current(owner)?.enabledGroups)
        apply(5, VerificationFeed(stream, 6, listOf(grant.copy(
            seq = 6, revision = 12, capabilityReview = grant.capabilityReview?.copy(submissionRevision = 11),
        )), false))
        assertEquals(emptySet(), repo.current(owner)?.enabledGroups)
        assertEquals(0L, repo.current(owner)?.preferenceVersion)
    }
}
