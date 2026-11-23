package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.vehicle.catalog.VehicleCatalog
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.sql.Date
import java.time.LocalDate
import java.util.Optional
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriverVerificationIdentityLockTest {
    @TempDir
    lateinit var tempDir: Path

    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .findAndAddModules()
        .build()

    private fun testPng(color: Color = Color.WHITE): ByteArray {
        val image = BufferedImage(720, 520, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().run {
                this.color = color
                fillRect(0, 0, 720, 520)
                dispose()
            }
        }
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, "png", out))
        return out.toByteArray()
    }

    private fun basePayload(
        cccdId: String = "999206000001",
        cccdName: String = "Tài Xế Kiểm Thử A",
        cccdDob: String = "2006-01-01",
        cccdExpiry: String? = "2031-12-19",
        gplxNumber: String = "999999000001",
        gplxName: String = "Tài Xế Kiểm Thử A",
        gplxDob: String = "2006-01-01",
        gplxClasses: List<String> = listOf("A1"),
        noExpiry: Boolean = true,
        expiryDate: String? = null,
    ): Map<String, Any?> = mapOf(
        "captures" to mapOf(
            "cccd_front" to mapOf("id" to "c1", "quality" to mapOf("warnings" to emptyList<String>())),
            "cccd_back" to mapOf("id" to "c2", "quality" to mapOf("warnings" to emptyList<String>())),
            "gplx_front" to mapOf("id" to "c3", "quality" to mapOf("warnings" to emptyList<String>())),
            "selfie" to mapOf("id" to "c4", "quality" to mapOf("warnings" to emptyList<String>())),
        ),
        "proposals" to emptyMap<String, Any?>(),
        "confirmed" to linkedMapOf<String, Any?>(
            "cccd_name" to cccdName,
            "cccd_id" to cccdId,
            "cccd_dob" to cccdDob,
            "cccd_expiry_date" to cccdExpiry,
            "cccd_gender" to "MALE",
            "gplx_number" to gplxNumber,
            "gplx_name" to gplxName,
            "gplx_dob" to gplxDob,
            "gplx_classes" to gplxClasses,
            "no_expiry" to noExpiry,
            "expiry_date" to expiryDate,
        ),
    )

    @Test
    fun `identity lock and duplicate warnings block activation across mismatch and duplicate cases`() {
        val ctx = IdentityVerificationContext(
            lockedCccdId = "999206000001",
            lockedFullName = "Tài Xế Kiểm Thử A",
            lockedDob = LocalDate.of(2006, 1, 1),
            verifiedGplxNumber = "999999000001",
            previousApprovedConfirmed = mapOf(
                "cccd_expiry_date" to "2031-12-19",
                "gplx_number" to "999999000001",
                "gplx_classes" to listOf("A1"),
                "no_expiry" to true,
                "expiry_date" to null,
            ),
            duplicateCccdAccount = true,
            duplicateGplxAccount = true,
        )
        val payload = basePayload(
            cccdId = "099999999999",
            cccdExpiry = "2041-12-19",
            gplxName = "Trần Văn Người Khác",
            gplxDob = "1995-01-01",
            gplxNumber = "999999999999",
            gplxClasses = listOf("B"),
            noExpiry = false,
            expiryDate = "2035-01-01",
        )
        val warnings = DriverVerificationWarnings.build(
            payload = payload,
            revision = 10,
            faceStatuses = listOf("POSSIBLE_MISMATCH"),
            identityContext = ctx,
        )
        val byCode = warnings.associateBy { it.code }
        assertTrue(byCode["LOCKED_CCCD_ID_CHANGED"]?.blocksSubmission == true)
        assertTrue(byCode["LOCKED_CCCD_ID_CHANGED"]?.blocksActivation == true)
        assertTrue(byCode["GPLX_HOLDER_MISMATCH"]?.blocksActivation == true)
        assertTrue(byCode["NAME_MISMATCH"]?.blocksActivation == true)
        assertTrue(byCode["DOB_MISMATCH"]?.blocksActivation == true)
        assertTrue(byCode["DUPLICATE_CCCD_ACROSS_ACCOUNTS"]?.blocksActivation == true)
        assertTrue(byCode["DUPLICATE_GPLX_ACROSS_ACCOUNTS"]?.blocksActivation == true)
        assertEquals("CRITICAL", byCode["FACE_POSSIBLE_MISMATCH"]?.severity)
        assertEquals("INFO", byCode["CCCD_RENEWAL_REQUIRES_REVIEW"]?.severity)
        assertEquals("INFO", byCode["GPLX_UPDATED_REQUIRES_REVIEW"]?.severity)
    }

    @Test
    fun `missing captures block submission and expired GPLX locks eligibility immediately`() {
        val incompleteSnapshot = mapOf(
            "captures" to emptyMap<String, Any?>(),
            "proposals" to emptyMap<String, Any?>(),
            "confirmed" to mapOf(
                "cccd_name" to "NGUYEN VAN QUYEN",
                "cccd_id" to "999206000001",
                "gplx_number" to "999999000001",
                "gplx_name" to "NGUYEN VAN QUYEN",
                "gplx_classes" to listOf("A1"),
                "no_expiry" to true,
                "expiry_date" to null,
            ),
        )
        val warnings = DriverVerificationWarnings.build(incompleteSnapshot, 2)
        assertTrue(warnings.any { it.code == "DOCUMENT_REQUIRED" && it.blocksSubmission })

        val today = capabilityToday()
        val projection = mock(VerificationProjectionRepository::class.java)
        val eligibility = DriverVerificationEligibility(projection, null)
        val driver = Driver().apply { id = 11; firebaseUid = "driver-exp"; verificationStatus = "APPROVED" }

        val validCar = LicenseEntitlement("B", LicenseValidity.HAS_EXPIRY, today.plusDays(10))
        val expiredMoto = LicenseEntitlement("A1", LicenseValidity.HAS_EXPIRY, today.minusDays(1))
        val carGroup = supportedCapabilityGroups.first { it.id == "car-auto" }
        val motoGroup = supportedCapabilityGroups.first { it.id == "moto-50-125" }
        val appId = UUID.randomUUID()

        `when`(projection.current(driver.firebaseUid)).thenReturn(
            DriverVerificationProjection(
                status = VerificationStatus.APPROVED,
                entitlements = listOf(validCar, expiredMoto),
                revision = 5,
                capabilities = listOf(
                    ApprovedCapability(carGroup.id, appId, 4, 5, LicenseEligibilityPolicy.VERSION, listOf(validCar)),
                    ApprovedCapability(motoGroup.id, appId, 4, 5, LicenseEligibilityPolicy.VERSION, listOf(expiredMoto)),
                ),
                enabledGroups = setOf(carGroup.id, motoGroup.id),
            ),
        )
        assertTrue(eligibility.mayGoOnline(driver, VehicleType.CAR))
        assertFalse(eligibility.mayGoOnline(driver, VehicleType.MOTORCYCLE))
    }

    @Test
    fun `reverify and cancel-reverify endpoints map 409 conflicts to envelope`() {
        val service = mock(DriverVerificationWorkflowService::class.java)
        val controller = DriverVerificationWorkflowController(service)
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
        val driver = AuthUser(2, "uid-driver-2", Role.DRIVER, "Test", null, "+84999999999", "OFFLINE")
        val appId = UUID.randomUUID()

        `when`(service.reverify(driver, appId, 3L)).thenReturn(mapOf("id" to appId.toString(), "status" to "DRAFT", "revision" to 4))
        `when`(service.cancelReverify(driver, appId, 99L))
            .thenThrow(VerificationWorkflowException(409, "REVISION_CONFLICT", "Hồ sơ đã thay đổi."))

        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(driver, null)
        try {
            mvc.perform(
                post("/ekyc/driver-verification/v1/applications/$appId/reverify")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("""{"revision":3}"""),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))

            mvc.perform(
                post("/api/driver/verification/v1/applications/$appId/cancel-reverify")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("""{"revision":99}"""),
            )
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("REVISION_CONFLICT"))
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VERIFICATION_E2E_PG_URL", matches = ".+")
    fun `end to end postgresql identity lock, non-destructive re-verification, and duplicate checks`() {
        val dbUrl = System.getenv("VERIFICATION_E2E_PG_URL")
        val dbUser = System.getenv("VERIFICATION_E2E_PG_USER") ?: "datxe"
        val dbPass = System.getenv("VERIFICATION_E2E_PG_PASSWORD") ?: "datxe"
        val schema = "idlock_" + UUID.randomUUID().toString().replace("-", "")
        val admin = JdbcTemplate(DriverManagerDataSource(dbUrl, dbUser, dbPass))
        admin.execute("CREATE SCHEMA $schema")
        try {
        val ds = DriverManagerDataSource(dbUrl, dbUser, dbPass).apply {
            connectionProperties = java.util.Properties().apply { setProperty("currentSchema", schema) }
        }
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).load().migrate()

        val jdbc = JdbcTemplate(ds)
        val projection = VerificationProjectionRepository(jdbc, mapper, requireSyncCheckpoint = false)
        val storage = DriverVerificationStorageService(tempDir.toString(), "synthetic-test-key", true)
        val driverState = mutableMapOf<String, Driver>()
        val driversRepo = mock(DriverRepository::class.java)
        `when`(driversRepo.findByFirebaseUid(org.mockito.ArgumentMatchers.anyString())).thenAnswer { inv ->
            Optional.ofNullable(driverState[inv.getArgument<String>(0)])
        }
        `when`(driversRepo.findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong())).thenAnswer { inv ->
            val id = inv.getArgument<Long>(0)
            Optional.ofNullable(driverState.values.firstOrNull { it.id == id })
        }
        `when`(driversRepo.save(any())).thenAnswer { inv ->
            val d = inv.getArgument<Driver>(0)
            jdbc.update(
                """UPDATE drivers
                   SET verification_status = ?, status = ?, online_vehicle_type = ?,
                       license_verified = ?, license_number = ?, license_class = ?, license_expiry_date = ?, full_name = ?
                   WHERE id = ?""",
                d.verificationStatus,
                d.status.name,
                d.onlineVehicleType?.name,
                d.licenseVerified,
                d.licenseNumber,
                d.licenseClass,
                d.licenseExpiryDate?.let { Date.valueOf(it) },
                d.fullName,
                d.id,
            )
            driverState[d.firebaseUid] = d
            d
        }

        val workflow = DriverVerificationWorkflowService(jdbc, mapper, storage, projection, driversRepo)
        val eligibility = DriverVerificationEligibility(projection, null)
        val capabilitySvc = DriverCapabilityService(driversRepo, projection, eligibility)

        fun insertDriver(uid: String, phone: String): Driver {
            val id = jdbc.queryForObject(
                "INSERT INTO drivers (firebase_uid, phone_number, verification_status, status) VALUES (?, ?, 'PENDING', 'OFFLINE') RETURNING id",
                Long::class.java,
                uid,
                phone,
            )!!
            return Driver().apply {
                this.id = id
                firebaseUid = uid
                phoneNumber = phone
                verificationStatus = "PENDING"
                status = DriverStatus.OFFLINE
            }.also { driverState[uid] = it }
        }

        val d1 = insertDriver("uid-e2e-driver-1", "+84900000001")
        val d2 = insertDriver("uid-e2e-driver-2", "+84900000002")
        val actor1 = AuthUser(d1.id, d1.firebaseUid, Role.DRIVER, "Quyền", null, d1.phoneNumber, "OFFLINE")
        val actor2 = AuthUser(d2.id, d2.firebaseUid, Role.DRIVER, "Khác", null, d2.phoneNumber, "OFFLINE")
        val admin = AuthUser(99, "uid-admin-1", Role.ADMIN, "Admin", null, null, "OFFLINE")

        // Seed initial upload + approval (A1, unlimited expiry) for Driver 1
        val created1 = workflow.create(actor1)
        val appId1 = UUID.fromString(created1["id"] as String)
        var rev = (created1["revision"] as Number).toLong()
        val imgBytes = testPng(Color.WHITE)
        for (slot in listOf("cccd_front", "cccd_back", "gplx_front", "gplx_back", "selfie")) {
            val res = workflow.replaceCapture(actor1, appId1, slot, rev, "image/png", imgBytes)
            rev = (res["revision"] as Number).toLong()
            val captureState = (res["document"] as Map<*, *>)["captures"] as Map<*, *>
            assertTrue(captureState.containsKey(slot))
            val warnings = res["verificationWarnings"] as List<*>
            assertFalse(warnings.any {
                it is VerificationWarningDto && it.code == "DOCUMENT_REQUIRED" && it.slot == slot
            })
        }
        assertTrue(workflow.readCaptureBytes(actor1, appId1, "selfie", false, null).second.contentEquals(imgBytes))
        assertEquals("NOT_FOUND", assertFailsWith<VerificationWorkflowException> {
            workflow.readCaptureBytes(actor2, appId1, "selfie", false, null)
        }.errorCode)
        assertEquals(403, assertFailsWith<VerificationWorkflowException> {
            workflow.readCaptureBytes(actor1, appId1, "selfie", true, null)
        }.httpStatus)
        assertEquals("NOT_FOUND", assertFailsWith<VerificationWorkflowException> {
            workflow.readCaptureBytes(admin, appId1, "selfie", true, null)
        }.errorCode)
        val initialDetails = linkedMapOf<String, Any?>(
            "cccd_name" to "Tài Xế Kiểm Thử A",
            "cccd_id" to "999206000001",
            "cccd_dob" to "2006-01-01",
            "cccd_expiry_date" to "2031-12-19",
            "cccd_gender" to "MALE",
            "gplx_number" to "999999000001",
            "gplx_name" to "Tài Xế Kiểm Thử A",
            "gplx_dob" to "2006-01-01",
            "gplx_classes" to listOf("A1"),
            "no_expiry" to true,
            "expiry_date" to null,
        )
        rev = (workflow.confirmFields(actor1, appId1, rev, initialDetails)["revision"] as Number).toLong()
        rev = (workflow.submit(actor1, appId1, "sub-1", rev, null, null)["revision"] as Number).toLong()
        assertTrue(workflow.readCaptureBytes(admin, appId1, "selfie", true, null).second.contentEquals(imgBytes))
        assertEquals("NOT_FOUND", assertFailsWith<VerificationWorkflowException> {
            workflow.readCaptureBytes(admin, appId1, "selfie", true, Long.MAX_VALUE)
        }.errorCode)
        rev = (workflow.decision(admin, appId1, VerificationDecisionRequest(rev, "UNDER_REVIEW"))["revision"] as Number).toLong()
        val approvedRes = workflow.decision(
            admin,
            appId1,
            VerificationDecisionRequest(
                revision = rev,
                status = "APPROVED",
                capabilityReview = CapabilityReview(
                    policyVersion = LicenseEligibilityPolicy.VERSION,
                    issuedOn = LocalDate.of(2024, 1, 1),
                    restriction = LicenseRestriction.VERIFIED_NONE,
                    selectedGroupIds = listOf("moto-50-125", "moto-electric-4-11"),
                ),
            ),
        )
        rev = (approvedRes["revision"] as Number).toLong()
        assertEquals(true, approvedRes["identityLocked"])

        // Enable moto-50-125 preference
        capabilitySvc.update(actor1, "moto-50-125", CapabilityPreferenceRequest(rev, 0L, true))
        assertTrue(eligibility.mayGoOnline(d1, VehicleType.MOTORCYCLE))
        assertFalse(eligibility.mayGoOnline(d1, VehicleType.CAR))

        // Driver detail hides raw CCCD inside maskedCccdId
        val driverView = workflow.detail(actor1, appId1, review = false)
        @Suppress("UNCHECKED_CAST")
        val lockedIdMap = driverView["lockedIdentity"] as Map<String, Any?>
        assertEquals("********0001", lockedIdMap["maskedCccdId"])
        assertNull(lockedIdMap["cccdId"])

        // Case: Re-verify -> cancel-reverify preserves APPROVED + previous captures
        val draft1 = workflow.reverify(actor1, appId1, rev)
        rev = (draft1["revision"] as Number).toLong()
        val selfieV2 = testPng(Color.LIGHT_GRAY) // Different bytes -> different sha256, same person allowed
        rev = (workflow.replaceCapture(actor1, appId1, "selfie", rev, "image/png", selfieV2)["revision"] as Number).toLong()
        // While editing in draft, old A1 capability STILL works!
        assertTrue(eligibility.mayGoOnline(d1, VehicleType.MOTORCYCLE))
        val cancelled = workflow.cancelReverify(actor1, appId1, rev)
        rev = (cancelled["revision"] as Number).toLong()
        assertEquals("APPROVED", cancelled["status"])
        assertTrue(eligibility.mayGoOnline(d1, VehicleType.MOTORCYCLE))

        // Case 3: Attempt to change locked cccd_id -> 409 IDENTITY_LOCKED
        rev = (workflow.reverify(actor1, appId1, rev)["revision"] as Number).toLong()
        val changedIdEx = assertFailsWith<VerificationWorkflowException> {
            workflow.confirmFields(actor1, appId1, rev, initialDetails + ("cccd_id" to "099999999999"))
        }
        assertEquals("IDENTITY_LOCKED", changedIdEx.errorCode)

        // Case 2, 4, 5, 7: Valid renewal + selfie change + GPLX upgrade A1 -> B (with A1 + B classes)
        val selfieV3 = testPng(Color.GRAY)
        rev = (workflow.replaceCapture(actor1, appId1, "selfie", rev, "image/png", selfieV3)["revision"] as Number).toLong()
        val upgradeDetails = initialDetails + mapOf(
            "cccd_expiry_date" to "2036-12-19",
            "gplx_number" to "250256099999",
            "gplx_classes" to listOf("A1", "B"),
            "no_expiry" to false,
            "expiry_date" to LocalDate.now().plusYears(5).toString(),
        )
        rev = (workflow.confirmFields(actor1, appId1, rev, upgradeDetails)["revision"] as Number).toLong()
        rev = (workflow.submit(actor1, appId1, "sub-upgrade", rev, null, null)["revision"] as Number).toLong()
        // Still APPROVED for old A1 during pending review, CAR NOT yet active!
        assertTrue(eligibility.mayGoOnline(d1, VehicleType.MOTORCYCLE))
        assertFalse(eligibility.mayGoOnline(d1, VehicleType.CAR))
        assertEquals(setOf("moto-50-125"), projection.current(d1.firebaseUid)?.effectiveGroups(capabilityToday()))
        assertFalse(eligibility.mayDrive(d1, VehicleType.CAR, Transmission.AUTO))

        // Case: Normal rejection (e.g., blurry upgrade photo) keeps prior valid A1 approval!
        rev = (workflow.decision(admin, appId1, VerificationDecisionRequest(rev, "UNDER_REVIEW"))["revision"] as Number).toLong()
        val rejectedUpgrade = workflow.decision(admin, appId1, VerificationDecisionRequest(rev, "REJECTED", reason = "Ảnh GPLX mờ"))
        rev = (rejectedUpgrade["revision"] as Number).toLong()
        assertEquals("REJECTED", rejectedUpgrade["status"])
        assertTrue(eligibility.mayGoOnline(d1, VehicleType.MOTORCYCLE))

        // Re-submit upgrade and approve -> preserves previously enabled moto-50-125 + unlocks car-auto!
        rev = (workflow.reverify(actor1, appId1, rev)["revision"] as Number).toLong()
        rev = (workflow.confirmFields(actor1, appId1, rev, upgradeDetails)["revision"] as Number).toLong()
        rev = (workflow.submit(actor1, appId1, "sub-upgrade-2", rev, null, null)["revision"] as Number).toLong()
        rev = (workflow.decision(admin, appId1, VerificationDecisionRequest(rev, "UNDER_REVIEW"))["revision"] as Number).toLong()
        val approvedUpgrade = workflow.decision(
            admin,
            appId1,
            VerificationDecisionRequest(
                revision = rev,
                status = "APPROVED",
                capabilityReview = CapabilityReview(
                    policyVersion = LicenseEligibilityPolicy.VERSION,
                    submissionRevision = rev - 1,
                    issuedOn = LocalDate.of(2025, 6, 1),
                    restriction = LicenseRestriction.VERIFIED_NONE,
                    selectedGroupIds = listOf("moto-50-125", "car-auto"),
                ),
            ),
        )
        rev = (approvedUpgrade["revision"] as Number).toLong()
        // Previously enabled moto-50-125 stays active immediately without re-enabling!
        assertTrue(eligibility.mayGoOnline(d1, VehicleType.MOTORCYCLE))
        assertNotNull(projection.current(d1.firebaseUid)?.capabilities?.firstOrNull { it.groupId == "car-auto" })

        // Case 6: Submitting GPLX bearing someone else's name -> identityHold = true & blocks APPROVED!
        rev = (workflow.reverify(actor1, appId1, rev)["revision"] as Number).toLong()
        rev = (workflow.confirmFields(actor1, appId1, rev, upgradeDetails + ("gplx_name" to "Trần Văn Người Khác"))["revision"] as Number).toLong()
        rev = (workflow.submit(actor1, appId1, "sub-other-gplx", rev, null, rev)["revision"] as Number).toLong()
        // Suspected identity discrepancy suspends ride-receiving!
        assertFalse(eligibility.mayGoOnline(d1, VehicleType.MOTORCYCLE))
        rev = (workflow.decision(admin, appId1, VerificationDecisionRequest(rev, "UNDER_REVIEW"))["revision"] as Number).toLong()
        val blockedApprove = assertFailsWith<VerificationWorkflowException> {
            workflow.decision(
                admin,
                appId1,
                VerificationDecisionRequest(
                    revision = rev,
                    status = "APPROVED",
                    capabilityReview = CapabilityReview(
                        policyVersion = LicenseEligibilityPolicy.VERSION,
                        submissionRevision = rev - 1,
                        issuedOn = LocalDate.of(2024, 1, 1),
                        restriction = LicenseRestriction.VERIFIED_NONE,
                        selectedGroupIds = listOf("car-auto"),
                    ),
                ),
            )
        }
        assertEquals("VERIFICATION_ACTIVATION_BLOCKED", blockedApprove.errorCode)

        // Case 9: Driver B tries to use Driver 1's verified CCCD / GPLX -> DUPLICATE blocks approval!
        val created2 = workflow.create(actor2)
        val appId2 = UUID.fromString(created2["id"] as String)
        var rev2 = (created2["revision"] as Number).toLong()
        for (slot in listOf("cccd_front", "cccd_back", "gplx_front", "gplx_back", "selfie")) {
            rev2 = (workflow.replaceCapture(actor2, appId2, slot, rev2, "image/png", imgBytes)["revision"] as Number).toLong()
        }
        val dupRes = workflow.confirmFields(actor2, appId2, rev2, initialDetails + ("gplx_number" to "250256099999"))
        rev2 = (dupRes["revision"] as Number).toLong()
        @Suppress("UNCHECKED_CAST")
        val dupWarnings = dupRes["verificationWarnings"] as List<VerificationWarningDto>
        assertTrue(dupWarnings.any { it.code == "DUPLICATE_CCCD_ACROSS_ACCOUNTS" && it.blocksActivation })
        assertTrue(dupWarnings.any { it.code == "DUPLICATE_GPLX_ACROSS_ACCOUNTS" && it.blocksActivation })

        rev2 = (workflow.submit(actor2, appId2, "sub-dup", rev2, null, rev2)["revision"] as Number).toLong()
        rev2 = (workflow.decision(admin, appId2, VerificationDecisionRequest(rev2, "UNDER_REVIEW"))["revision"] as Number).toLong()
        val blockedDup = assertFailsWith<VerificationWorkflowException> {
            workflow.decision(
                admin,
                appId2,
                VerificationDecisionRequest(
                    revision = rev2,
                    status = "APPROVED",
                    capabilityReview = CapabilityReview(
                        policyVersion = LicenseEligibilityPolicy.VERSION,
                        submissionRevision = rev2 - 1,
                        issuedOn = LocalDate.of(2024, 1, 1),
                        restriction = LicenseRestriction.VERIFIED_NONE,
                        selectedGroupIds = listOf("moto-50-125"),
                    ),
                ),
            )
        }
        assertEquals("VERIFICATION_ACTIVATION_BLOCKED", blockedDup.errorCode)
        assertFalse(eligibility.mayGoOnline(d2, VehicleType.MOTORCYCLE))

        // Rejected initial application (never approved) can start a fresh draft via reverify and resubmit
        val rejectedInitial = workflow.decision(
            admin,
            appId2,
            VerificationDecisionRequest(rev2, "REJECTED", reason = "Giấy tờ trùng tài khoản khác"),
        )
        rev2 = (rejectedInitial["revision"] as Number).toLong()
        assertEquals("REJECTED", rejectedInitial["status"])
        assertNull(rejectedInitial["approvedRevision"])

        val reopened2 = workflow.reverify(actor2, appId2, rev2)
        rev2 = (reopened2["revision"] as Number).toLong()
        assertEquals("DRAFT", reopened2["status"])
        assertEquals(false, reopened2["identityHold"])

        val validD2Details = initialDetails + mapOf(
            "cccd_id" to "999206000002",
            "cccd_name" to "Khác",
            "gplx_name" to "Khác",
            "gplx_number" to "250256000002",
        )
        val savedGplxQr = mapOf("status" to "DECODED_VALID", "documentSide" to "BACK", "boundToCapture" to true)
        jdbc.update(
            "UPDATE driver_verification_applications SET gplx_qr_live_scan_json = ? WHERE id = ?",
            mapper.writeValueAsString(savedGplxQr), appId2,
        )
        rev2 = (workflow.confirmFields(actor2, appId2, rev2, validD2Details)["revision"] as Number).toLong()
        val resubmitted2 = workflow.submit(actor2, appId2, "sub-d2-retry", rev2, null, null)
        assertEquals("SUBMITTED", resubmitted2["status"])
        val snapshot = jdbc.queryForObject(
            "SELECT snapshot_json FROM driver_verification_submissions WHERE application_id = ? AND revision = ?",
            String::class.java, appId2, resubmitted2["revision"],
        )!!
        assertEquals(savedGplxQr, mapper.readValue(snapshot, Map::class.java)["gplxQrLiveScan"])
        } finally {
            admin.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }
}
