package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.websocket.PayloadType
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverDocumentRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import com.nhom15.datxebackend.ekyc.EkycClient
import com.nhom15.datxebackend.ekyc.EkycDocumentSlot
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.DriverAccountNotificationPayload
import com.nhom15.datxebackend.tracking.service.LocationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.SqlParameterValue
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val EDITABLE_STATES = setOf("DRAFT", "NEEDS_MORE_INFO")
private val REVERIFY_SOURCE_STATES = setOf("APPROVED", "REJECTED")
private val TRANSITIONS = mapOf(
    "SUBMITTED" to setOf("UNDER_REVIEW"),
    "UNDER_REVIEW" to setOf("NEEDS_MORE_INFO", "APPROVED", "REJECTED"),
    "APPROVED" to setOf("REVOKED", "NEEDS_MORE_INFO"),
    "REVOKED" to setOf("NEEDS_MORE_INFO"),
)
private const val FACE_CONSENT_VERSION = "face-comparison-v1"
private val SEVERE_IDENTITY_CONFLICT_CODES = setOf(
    "LOCKED_CCCD_ID_CHANGED",
    "LOCKED_IDENTITY_NAME_MISMATCH",
    "LOCKED_IDENTITY_DOB_MISMATCH",
    "GPLX_HOLDER_MISMATCH",
    "NAME_MISMATCH",
    "DOB_MISMATCH",
    "DUPLICATE_CCCD_ACROSS_ACCOUNTS",
    "DUPLICATE_GPLX_ACROSS_ACCOUNTS",
    "FACE_POSSIBLE_MISMATCH",
)
private val NON_OVERRIDABLE_BLOCKER_CODES = setOf(
    "CCCD_EXPIRED",
    "GPLX_EXPIRED",
    "GPLX_EXPIRY_UNKNOWN",
    "GPLX_CLASS_UNKNOWN",
    "GPLX_RESTRICTION_UNKNOWN",
    "DOCUMENT_REQUIRED",
    "DUPLICATE_CCCD_ACROSS_ACCOUNTS",
    "DUPLICATE_GPLX_ACROSS_ACCOUNTS",
)

data class VerificationDecisionRequest(
    val revision: Long,
    val status: String,
    val reason: String? = null,
    val capabilityReview: CapabilityReview? = null,
    val captures: List<String> = emptyList(),
    val fields: List<String> = emptyList(),
    val allowIdentityOverride: Boolean = false,
)

@Service
class DriverVerificationWorkflowService @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val storage: DriverVerificationStorageService,
    private val projection: VerificationProjectionRepository,
    private val driverRepository: DriverRepository,
    private val documentRepository: DriverDocumentRepository? = null,
    private val skillRepository: DriverSkillRepository? = null,
    private val ekycClient: EkycClient? = null,
    private val locationService: LocationService? = null,
    private val notificationPort: NotificationPort? = null,
    @Value("\${app.verification.uploads-per-minute:12}") private val uploadsPerMinute: Int = 12,
    @Value("\${app.verification.lookup-hmac-key:\${app.verification.encryption-key:}}") lookupHmacKey: String = "",
    transactionManager: PlatformTransactionManager? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val txTemplate: TransactionTemplate? = transactionManager?.let(::TransactionTemplate)
    private val lookupHmacSecret: ByteArray = lookupHmacKey.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
    } ?: ByteArray(32).also { SecureRandom().nextBytes(it) }

    private data class ApplicationRow(
        val id: UUID,
        val ownerUid: String,
        val status: String,
        val revision: Long,
        val confirmedFieldsJson: String?,
        val confirmationCurrent: Boolean,
        val submissionRevision: Long?,
        val submittedAt: Instant?,
        val resubmissionCount: Int,
        val reason: String?,
        val requestedChangesJson: String?,
        val faceConsentJson: String?,
        val updatedAt: Instant,
        val approvedRevision: Long? = null,
        val approvedSubmissionRevision: Long? = null,
        val lockedCccdId: String? = null,
        val lockedFullName: String? = null,
        val lockedDob: LocalDate? = null,
        val verifiedGplxNumber: String? = null,
        val identityHold: Boolean = false,
        val identityHoldReason: String? = null,
        val qrLiveScanJson: String? = null,
        val gplxQrLiveScanJson: String? = null,
    )

    private data class CaptureRow(
        val id: UUID,
        val applicationId: UUID,
        val slot: String,
        val storageKey: String,
        val mimeType: String,
        val byteSize: Int,
        val sha256: String,
        val qualityJson: String,
        val proposalJson: String?,
        val capturedRevision: Long,
    )

    private sealed interface ExtractStageA {
        data class Cached(val response: Map<String, Any?>) : ExtractStageA
        data class Pending(
            val applicationId: UUID,
            val expectedRevision: Long,
            val capture: CaptureRow,
        ) : ExtractStageA
    }

    private inline fun <T : Any> inTransaction(crossinline block: () -> T): T =
        txTemplate?.execute { block() } ?: block()

    fun me(actor: AuthUser): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>(
            "uid" to actor.firebaseUid,
            "admin" to (actor.role == Role.ADMIN),
        )
        if (ekycClient?.faceComparisonEnabled == true) {
            result["faceConsentVersion"] = FACE_CONSENT_VERSION
        }
        return result
    }

    @Transactional
    fun create(actor: AuthUser): Map<String, Any?> {
        val existing = findByOwner(actor.firebaseUid)
        if (existing != null) return metadata(existing)

        val id = UUID.randomUUID()
        val now = Instant.now()
        val prefill = buildLegacyConfirmedPrefill(actor)
        val prefillJson = prefill?.let { mapper.writeValueAsString(it) }
        jdbc.update(
            """INSERT INTO driver_verification_applications
               (id, owner_uid, status, revision, confirmed_fields, confirmation_current, created_at, updated_at)
               VALUES (?, ?, 'DRAFT', 0, ?, false, ?, ?)""",
            id,
            actor.firebaseUid,
            SqlParameterValue(Types.VARCHAR, prefillJson),
            Timestamp.from(now),
            Timestamp.from(now),
        )
        audit(id, 0L, actor, "CREATE", emptyMap(), review = false)
        return metadata(requireById(id))
    }

    @Transactional(readOnly = true)
    fun list(actor: AuthUser, limit: Int, offset: Int, review: Boolean = false): List<Map<String, Any?>> {
        if (review && actor.role != Role.ADMIN) {
            throw VerificationWorkflowException(403, "FORBIDDEN", "Bạn không có quyền truy cập.")
        }
        val sql = if (review) {
            """SELECT * FROM driver_verification_applications
               WHERE submission_revision IS NOT NULL
               ORDER BY updated_at DESC, id LIMIT ? OFFSET ?"""
        } else {
            """SELECT * FROM driver_verification_applications
               WHERE owner_uid = ?
               ORDER BY updated_at DESC, id LIMIT ? OFFSET ?"""
        }
        val rows = if (review) {
            jdbc.query(sql, { rs, _ -> rs.toAppRow() }, limit, offset)
        } else {
            jdbc.query(sql, { rs, _ -> rs.toAppRow() }, actor.firebaseUid, limit, offset)
        }
        return rows.map(::metadata)
    }

    @Transactional(readOnly = true)
    fun detail(
        actor: AuthUser,
        applicationId: UUID,
        snapshotRevision: Long? = null,
        review: Boolean = false,
    ): Map<String, Any?> {
        val row = requireAccessibleApp(applicationId, actor, review = review, forUpdate = false)
        return buildDetailResponse(row, snapshotRevision = snapshotRevision, review = review)
    }

    private fun buildDetailResponse(
        row: ApplicationRow,
        snapshotRevision: Long? = null,
        review: Boolean = false,
    ): MutableMap<String, Any?> {
        val data = metadata(row, includeRawCccdId = review).toMutableMap()
        data["requestedChanges"] = requestedChanges(row)

        val approvedSnapshot = row.approvedSubmissionRevision?.let { rev ->
            runCatching { loadSubmissionSnapshot(row.id, rev) }.getOrNull()
        }
        if (approvedSnapshot != null) {
            data["approvedDocument"] = approvedSnapshot
        }

        val document: MutableMap<String, Any?>
        if (review || snapshotRevision != null) {
            val selected = snapshotRevision ?: row.submissionRevision
                ?: throw VerificationWorkflowException(404, "NOT_FOUND", "Chưa có bản nộp.")
            val snapshotMap = loadSubmissionSnapshot(row.id, selected).toMutableMap()
            if (review) {
                val submissions = jdbc.query(
                    """SELECT revision, submitted_at FROM driver_verification_submissions
                       WHERE application_id = ? ORDER BY revision DESC""",
                    { rs, _ ->
                        mapOf(
                            "revision" to rs.getLong("revision"),
                            "submittedAt" to rs.getTimestamp("submitted_at").toInstant().toString(),
                        )
                    },
                    row.id,
                )
                data["submissions"] = submissions
                val compareRev = row.approvedSubmissionRevision?.takeIf { it != selected }
                    ?: submissions.mapNotNull { it["revision"] as? Long }.firstOrNull { it < selected }
                if (compareRev != null) {
                    val prior = (loadSubmissionSnapshot(row.id, compareRev)["confirmed"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
                    val latest = (snapshotMap["confirmed"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
                    data["editedFields"] = latest.keys.mapNotNull { it as? String }
                        .filter { latest[it] != prior[it] }
                        .sorted()
                }
                data["submittedAt"] = row.submittedAt?.toString() ?: snapshotMap["submittedAt"]
                data["warningCount"] = snapshotMap["warningCount"] ?: 0
                data["resubmissionCount"] = row.resubmissionCount
                data["faceComparisonAvailable"] = ekycClient?.faceComparisonEnabled == true
            }
            document = snapshotMap
        } else {
            document = buildWorkingDocument(row).toMutableMap()
        }
        data["document"] = document

        val warningSourceDoc = if (!review && snapshotRevision == null && row.status !in EDITABLE_STATES && row.submissionRevision != null) {
            loadSubmissionSnapshot(row.id, row.submissionRevision)
        } else {
            document
        }
        val warningRevision = (warningSourceDoc["revision"] as? Number)?.toLong() ?: row.revision
        @Suppress("UNCHECKED_CAST")
        val warningConfirmed = warningSourceDoc["confirmed"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val snapshotFaceStatuses = (warningSourceDoc["faceStatuses"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        data["verificationWarnings"] = DriverVerificationWarnings.build(
            payload = warningSourceDoc,
            revision = warningRevision,
            faceStatuses = snapshotFaceStatuses,
            identityContext = buildIdentityContext(row, warningConfirmed, warningSourceDoc),
        )
        data["eligibilityStatus"] = buildEligibilitySummary(row)

        val revisionCutoff = if (review && row.status in EDITABLE_STATES) row.submissionRevision else null
        val auditSql = if (revisionCutoff != null) {
            """SELECT revision, actor_uid, actor_role, action, metadata_json, created_at
               FROM driver_verification_audit
               WHERE application_id = ?
                 AND (revision <= ? OR actor_role = 'admin')
               ORDER BY revision DESC, id DESC LIMIT 100"""
        } else {
            """SELECT revision, actor_uid, actor_role, action, metadata_json, created_at
               FROM driver_verification_audit
               WHERE application_id = ?
               ORDER BY revision DESC, id DESC LIMIT 100"""
        }
        val auditMapper = org.springframework.jdbc.core.RowMapper { rs, _ ->
            val item = linkedMapOf<String, Any?>(
                "revision" to rs.getLong("revision"),
                "role" to rs.getString("actor_role"),
                "action" to rs.getString("action"),
                "at" to rs.getTimestamp("created_at").toInstant().toString(),
                "metadata" to readMap(rs.getString("metadata_json")),
            )
            if (review) item["actor"] = rs.getString("actor_uid")
            item
        }
        val auditEvents = if (revisionCutoff != null) {
            jdbc.query(auditSql, auditMapper, row.id, revisionCutoff)
        } else {
            jdbc.query(auditSql, auditMapper, row.id)
        }
        data["history"] = auditEvents
        return data
    }

    @Transactional
    fun reverify(
        actor: AuthUser,
        applicationId: UUID,
        expectedRevision: Long,
    ): Map<String, Any?> {
        val row = requireAccessibleApp(applicationId, actor, review = false, forUpdate = true)
        checkRevision(row, expectedRevision, editable = false)
        if (row.status !in REVERIFY_SOURCE_STATES) {
            throw VerificationWorkflowException(409, "INVALID_TRANSITION", "Chỉ có thể cập nhật giấy tờ khi hồ sơ đã được duyệt hoặc bị từ chối.")
        }
        val approvedConfirmed = row.confirmedFieldsJson ?: row.approvedSubmissionRevision?.let { rev ->
            runCatching { mapper.writeValueAsString(loadSubmissionSnapshot(row.id, rev)["confirmed"]) }.getOrNull()
        }
        val nextRevision = expectedRevision + 1L
        val clearUnapprovedHold = row.approvedRevision == null
        jdbc.update(
            """UPDATE driver_verification_applications
               SET status = 'DRAFT', revision = ?, confirmed_fields = COALESCE(confirmed_fields, ?),
                   confirmation_current = false, decision_reason = NULL, requested_changes = NULL,
                   qr_live_scan_json = NULL, gplx_qr_live_scan_json = NULL,
                   identity_hold = CASE WHEN ? THEN false ELSE identity_hold END,
                   identity_hold_reason = CASE WHEN ? THEN NULL ELSE identity_hold_reason END,
                   updated_at = CURRENT_TIMESTAMP
               WHERE id = ?""",
            nextRevision,
            SqlParameterValue(Types.VARCHAR, approvedConfirmed),
            clearUnapprovedHold,
            clearUnapprovedHold,
            row.id,
        )
        // Deliberately do NOT update driver_verification_projection or drivers here so verified drivers retain existing valid ride eligibility during draft/update.
        audit(row.id, nextRevision, actor, "START_REVERIFICATION", mapOf("fromStatus" to row.status), review = false)
        return buildDetailResponse(requireById(row.id), review = false)
    }

    @Transactional
    fun cancelReverify(
        actor: AuthUser,
        applicationId: UUID,
        expectedRevision: Long,
    ): Map<String, Any?> {
        val row = requireAccessibleApp(applicationId, actor, review = false, forUpdate = true)
        checkRevision(row, expectedRevision, editable = false)
        if (row.status != "DRAFT" || row.approvedRevision == null || row.approvedSubmissionRevision == null || row.identityHold) {
            throw VerificationWorkflowException(409, "INVALID_TRANSITION", "Chỉ có thể hủy cập nhật khi đang ở bản nháp của hồ sơ đã duyệt.")
        }
        val approvedSnapshot = loadSubmissionSnapshot(row.id, row.approvedSubmissionRevision)
        @Suppress("UNCHECKED_CAST")
        val approvedCaps = approvedSnapshot["captures"] as? Map<String, Any?> ?: emptyMap()
        val approvedCapIds = approvedCaps.values.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .toSet()

        jdbc.update(
            "UPDATE driver_verification_captures SET retired_at = CURRENT_TIMESTAMP WHERE application_id = ? AND retired_at IS NULL",
            row.id,
        )
        for (capId in approvedCapIds) {
            jdbc.update(
                "UPDATE driver_verification_captures SET retired_at = NULL WHERE application_id = ? AND id = ?",
                row.id,
                capId,
            )
        }
        val nextRevision = expectedRevision + 1L
        jdbc.update(
            """UPDATE driver_verification_applications
               SET status = 'APPROVED', revision = ?, submission_revision = ?,
                   confirmed_fields = ?, confirmation_current = true,
                   qr_live_scan_json = NULL, gplx_qr_live_scan_json = NULL,
                   requested_changes = NULL, updated_at = CURRENT_TIMESTAMP
               WHERE id = ?""",
            nextRevision,
            row.approvedSubmissionRevision,
            SqlParameterValue(Types.VARCHAR, approvedSnapshot["confirmed"]?.let { mapper.writeValueAsString(it) }),
            row.id,
        )
        audit(row.id, nextRevision, actor, "CANCEL_REVERIFICATION", emptyMap(), review = false)
        return buildDetailResponse(requireById(row.id), review = false)
    }

    @Transactional
    fun replaceCapture(
        actor: AuthUser,
        applicationId: UUID,
        slot: String,
        expectedRevision: Long,
        mimeType: String,
        bytes: ByteArray,
    ): Map<String, Any?> {
        if (slot !in DriverVerificationWarnings.ALL_SLOTS) {
            throw VerificationWorkflowException(400, "INVALID_REQUEST", "Loại ảnh không hợp lệ.")
        }
        val row = requireAccessibleApp(applicationId, actor, review = false, forUpdate = true)
        checkRevision(row, expectedRevision, editable = true)
        enforceRequestedSlot(row, slot)
        checkUploadRateLimit(actor.firebaseUid)

        val checked = storage.inspectImage(bytes, mimeType)
        val activeCaps = loadActiveCaptures(row.id)
        val previous = activeCaps[slot]
        if (previous != null && previous.sha256 == checked.sha256 && previous.mimeType.equals(mimeType, ignoreCase = true)) {
            return withWarnings(row, buildWorkingDocument(row))
        }

        val objectKey = storage.store(bytes)
        val captureId = UUID.randomUUID()
        val qualityMap = mapOf(
            "width" to checked.width,
            "height" to checked.height,
            "warnings" to checked.warnings,
        )
        try {
            if (previous != null) {
                jdbc.update(
                    "UPDATE driver_verification_captures SET retired_at = CURRENT_TIMESTAMP WHERE id = ?",
                    previous.id,
                )
            }
            jdbc.update(
                """INSERT INTO driver_verification_captures
                   (id, application_id, slot, storage_key, mime_type, byte_size, sha256, quality_json, proposal_json, captured_revision, uploaded_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, CURRENT_TIMESTAMP)""",
                captureId,
                row.id,
                slot,
                objectKey,
                mimeType.lowercase(),
                checked.size,
                checked.sha256,
                mapper.writeValueAsString(qualityMap),
                expectedRevision + 1L,
            )
        } catch (ex: Exception) {
            storage.deleteQuietly(objectKey)
            throw ex
        }

        val nextRevision = expectedRevision + 1L
        val cccdLiveCleared = shouldClearQrLiveScan(row.qrLiveScanJson, slot, previous?.id, "cccd")
        val gplxLiveCleared = shouldClearQrLiveScan(row.gplxQrLiveScanJson, slot, previous?.id, "gplx")
        jdbc.update(
            """UPDATE driver_verification_applications
               SET revision = ?, confirmation_current = false,
                   qr_live_scan_json = CASE WHEN ? THEN NULL ELSE qr_live_scan_json END,
                   gplx_qr_live_scan_json = CASE WHEN ? THEN NULL ELSE gplx_qr_live_scan_json END,
                   updated_at = CURRENT_TIMESTAMP
               WHERE id = ?""",
            nextRevision,
            cccdLiveCleared,
            gplxLiveCleared,
            row.id,
        )
        val refreshed = requireById(row.id)
        syncQrCccdIdHmac(refreshed, buildWorkingDocument(refreshed))
        audit(
            row.id,
            nextRevision,
            actor,
            if (previous != null) "REPLACE_CAPTURE" else "ADD_CAPTURE",
            mapOf("slot" to slot),
            review = false,
        )
        val updated = requireById(row.id)
        return withWarnings(updated, buildWorkingDocument(updated))
    }

    @Transactional(readOnly = true)
    fun readCaptureBytes(
        actor: AuthUser,
        applicationId: UUID,
        slot: String,
        review: Boolean,
        snapshotRevision: Long?,
    ): Pair<String, ByteArray> {
        if (slot !in DriverVerificationWarnings.ALL_SLOTS) {
            throw VerificationWorkflowException(400, "INVALID_REQUEST", "Loại ảnh không hợp lệ.")
        }
        val row = requireAccessibleApp(applicationId, actor, review = review, forUpdate = false)
        val doc = if (review || snapshotRevision != null) {
            val targetRev = snapshotRevision ?: row.submissionRevision
                ?: throw VerificationWorkflowException(404, "NOT_FOUND", "Không có bản nộp.")
            loadSubmissionSnapshot(row.id, targetRev)
        } else {
            buildWorkingDocument(row)
        }
        @Suppress("UNCHECKED_CAST")
        val captures = doc["captures"] as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val captureMeta = captures[slot] as? Map<String, Any?>
            ?: throw VerificationWorkflowException(404, "NOT_FOUND", "Không tìm thấy ảnh cho mục này.")
        val capIdStr = captureMeta["id"] as? String
            ?: throw VerificationWorkflowException(404, "NOT_FOUND", "Không tìm thấy mã ảnh.")
        val capId = UUID.fromString(capIdStr)
        val capRow = jdbc.query(
            "SELECT * FROM driver_verification_captures WHERE id = ? AND application_id = ?",
            { rs, _ -> rs.toCaptureRow() },
            capId,
            row.id,
        ).firstOrNull() ?: throw VerificationWorkflowException(404, "NOT_FOUND", "Ảnh không tồn tại.")
        val bytes = storage.read(capRow.storageKey, capRow.byteSize, capRow.sha256)
        return capRow.mimeType to bytes
    }

    fun extractSlot(
        actor: AuthUser,
        applicationId: UUID,
        slot: String,
        expectedRevision: Long,
    ): Map<String, Any?> {
        val ekycSlot = EkycDocumentSlot.entries.firstOrNull { it.wireName == slot }
            ?: throw VerificationWorkflowException(400, "INVALID_REQUEST", "Mặt giấy tờ không hỗ trợ trích xuất.")
        if (ekycClient == null) {
            throw VerificationWorkflowException(503, "ENGINE_BUSY", "Dịch vụ đọc giấy tờ tự động chưa sẵn sàng. Bạn vẫn có thể nhập tay.")
        }

        // Stage A: Validate ownership/revision/capture inside a short transaction and release row lock before HTTP I/O.
        val stageA = inTransaction {
            val row = requireAccessibleApp(applicationId, actor, review = false, forUpdate = true)
            checkRevision(row, expectedRevision, editable = true)
            enforceRequestedSlot(row, slot)
            val capRow = loadActiveCaptures(row.id)[slot]
                ?: throw VerificationWorkflowException(404, "NOT_FOUND", "Chưa tải ảnh cho mặt giấy tờ này.")
            if (!capRow.proposalJson.isNullOrBlank()) {
                val cachedResponse = withWarnings(row, buildWorkingDocument(row)).toMutableMap()
                cachedResponse["proposal"] = mapper.readTree(capRow.proposalJson)
                ExtractStageA.Cached(cachedResponse)
            } else {
                ExtractStageA.Pending(row.id, expectedRevision, capRow)
            }
        }
        if (stageA is ExtractStageA.Cached) return stageA.response

        val pending = stageA as ExtractStageA.Pending
        val capRow = pending.capture

        // Stage B: Read capture bytes and call Modal strictly outside any DB transaction or PostgreSQL lock.
        val bytes = storage.read(capRow.storageKey, capRow.byteSize, capRow.sha256)
        val mediaType = MediaType.parseMediaType(capRow.mimeType)
        val proposalNode = try {
            ekycClient.extract(ekycSlot, mediaType, ByteArrayInputStream(bytes))
        } catch (ex: AppException) {
            val msg = ex.message ?: ex.errorCode.defaultMessage
            val mapped = when (ex.errorCode) {
                ErrorCode.EKYC_TIMEOUT -> VerificationWorkflowException(504, "ENGINE_TIMEOUT", msg)
                ErrorCode.EKYC_INVALID_IMAGE -> VerificationWorkflowException(400, "INVALID_IMAGE", msg)
                ErrorCode.EKYC_IMAGE_TOO_LARGE -> VerificationWorkflowException(413, "IMAGE_TOO_LARGE", msg)
                else -> VerificationWorkflowException(503, "ENGINE_BUSY", msg)
            }
            throw mapped
        }

        // Stage C: Re-acquire row lock briefly, fail closed on concurrent changes, and persist extraction atomically.
        return inTransaction {
            val row = requireAccessibleApp(pending.applicationId, actor, review = false, forUpdate = true)
            checkRevision(row, pending.expectedRevision, editable = true)
            enforceRequestedSlot(row, slot)
            val activeCap = loadActiveCaptures(row.id)[slot]
            if (activeCap == null || activeCap.id != capRow.id || activeCap.sha256 != capRow.sha256) {
                throw VerificationWorkflowException(409, "REVISION_CONFLICT", "Ảnh giấy tờ đã thay đổi trong lúc xử lý, hãy làm mới trạng thái.")
            }

            val nextRevision = pending.expectedRevision + 1L
            val qrNode = proposalNode.path("qr")
            if (qrNode is ObjectNode) {
                qrNode.put("attachmentId", capRow.id.toString())
                qrNode.put("revision", nextRevision)
                qrNode.put("source", "QR_IMAGE")
                val qrStatus = qrNode.path("status").asString("")
                qrNode.put("boundToCapture", qrStatus in setOf("DECODED_VALID", "CONFLICT"))
            }

            val proposalJson = mapper.writeValueAsString(proposalNode)
            jdbc.update(
                "UPDATE driver_verification_captures SET proposal_json = ? WHERE id = ?",
                proposalJson,
                capRow.id,
            )
            jdbc.update(
                "UPDATE driver_verification_applications SET revision = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                nextRevision,
                row.id,
            )
            val refreshed = requireById(row.id)
            syncQrCccdIdHmac(refreshed, buildWorkingDocument(refreshed))
            audit(row.id, nextRevision, actor, "EXTRACT", mapOf("slot" to slot), review = false)
            val updated = requireById(row.id)
            val result = withWarnings(updated, buildWorkingDocument(updated)).toMutableMap()
            result["proposal"] = proposalNode
            result
        }
    }

    @Transactional
    fun submitQrLiveScan(
        actor: AuthUser,
        applicationId: UUID,
        body: QrScanBody,
    ): Map<String, Any?> {
        val rawPayload = body.rawPayload.trim()
        if (rawPayload.isEmpty() || rawPayload.length > 1024) {
            throw VerificationWorkflowException(400, "INVALID_REQUEST", "Chuỗi mã QR không hợp lệ.")
        }
        val documentSide = (body.documentSide ?: "UNKNOWN").trim().uppercase()
        if (documentSide !in setOf("FRONT", "BACK", "UNKNOWN")) {
            throw VerificationWorkflowException(400, "INVALID_REQUEST", "Mặt giấy tờ không hợp lệ.")
        }

        val row = requireAccessibleApp(applicationId, actor, review = false, forUpdate = true)
        checkRevision(row, body.revision, editable = true)

        val documentType = (body.documentType ?: "CCCD").trim().uppercase()
        if (documentType !in setOf("CCCD", "GPLX")) {
            throw VerificationWorkflowException(400, "INVALID_REQUEST", "Loại giấy tờ QR không hợp lệ.")
        }
        val docPrefix = documentType.lowercase()
        val slotsPair = listOf("${docPrefix}_front", "${docPrefix}_back")
        val targetSlot = when (documentSide) {
            "FRONT" -> "${docPrefix}_front"
            "BACK" -> "${docPrefix}_back"
            else -> null
        }
        val targets = requestedChanges(row)
        if (targets != null) {
            val allowed = (targets["captures"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
            if (targetSlot != null && targetSlot !in allowed) {
                throw VerificationWorkflowException(403, "FORBIDDEN", "Không được phép cập nhật QR cho mục này.")
            }
            if (targetSlot == null && allowed.intersect(slotsPair.toSet()).isEmpty()) {
                throw VerificationWorkflowException(403, "FORBIDDEN", "Không được phép cập nhật QR cho mục này.")
            }
        }

        val activeCaptures = loadActiveCaptures(row.id)
        val boundAttachmentId: String? = if (body.attachmentId != null) {
            val reqId = body.attachmentId
            val valid = if (targetSlot != null) {
                activeCaptures[targetSlot]?.id == reqId
            } else {
                activeCaptures[slotsPair[0]]?.id == reqId || activeCaptures[slotsPair[1]]?.id == reqId
            }
            if (!valid) {
                throw VerificationWorkflowException(409, "REVISION_CONFLICT", "Ảnh giấy tờ hiện hành đã thay đổi hoặc không hợp lệ.")
            }
            reqId.toString()
        } else if (targetSlot != null) {
            activeCaptures[targetSlot]?.id?.toString()
        } else {
            val fallbackCap = if (documentType == "GPLX") {
                activeCaptures[slotsPair[1]] ?: activeCaptures[slotsPair[0]]
            } else {
                activeCaptures[slotsPair[0]] ?: activeCaptures[slotsPair[1]]
            }
            fallbackCap?.id?.toString()
        }

        val defaultSlot = if (documentType == "GPLX") "gplx_back" else "cccd_front"
        val parsed = CccdQrParser.parse(rawPayload, slot = targetSlot ?: defaultSlot)
        if (parsed.status != CccdQrStatus.DECODED_VALID) {
            val qrRejected = linkedMapOf<String, Any?>(
                "status" to parsed.status.name,
                "source" to "QR_LIVE_SCAN",
                "documentSide" to documentSide,
                "cardVariant" to if (documentType == "GPLX") "GPLX_PET" else null,
                "fields" to emptyMap<String, Any?>(),
                "missingFields" to emptyList<String>(),
                "conflictFields" to emptyList<String>(),
                "warnings" to parsed.warnings,
                "crossValidationStatus" to "NOT_APPLICABLE",
                "attachmentId" to boundAttachmentId,
                "revision" to row.revision,
                "boundToCapture" to false,
            )
            val result = withWarnings(row, buildWorkingDocument(row)).toMutableMap()
            result["qr"] = qrRejected
            return result
        }

        val ocrFields = collectActiveOcrFields(activeCaptures, slotsPair)
        val summary = CccdQrParser.compareQrAndOcr(parsed.fields, ocrFields)
        val qrStatus = if (summary.conflictFields.isNotEmpty()) CccdQrStatus.CONFLICT.name else CccdQrStatus.DECODED_VALID.name
        val warningsList = parsed.warnings.toMutableList()
        if (summary.diacriticMismatchFields.isNotEmpty() && "QR_OCR_NAME_DIACRITIC_MISMATCH" !in warningsList) {
            warningsList += "QR_OCR_NAME_DIACRITIC_MISMATCH"
        }
        if (summary.conflictFields.any { it !in summary.diacriticMismatchFields } && "QR_OCR_MISMATCH" !in warningsList) {
            warningsList += "QR_OCR_MISMATCH"
        }
        if (summary.status == "INSUFFICIENT_EVIDENCE") {
            warningsList += "QR_OCR_INSUFFICIENT_EVIDENCE"
        }

        val nextRevision = body.revision + 1L
        val qrEntry = linkedMapOf<String, Any?>(
            "status" to qrStatus,
            "source" to "QR_LIVE_SCAN",
            "documentSide" to documentSide,
            "cardVariant" to if (documentType == "GPLX") "GPLX_PET" else null,
            "fields" to parsed.fields,
            "missingFields" to parsed.missingFields,
            "conflictFields" to summary.conflictFields,
            "warnings" to warningsList,
            "crossValidationStatus" to summary.status,
            "attachmentId" to boundAttachmentId,
            "revision" to nextRevision,
            "boundToCapture" to false,
        )
        val qrJson = mapper.writeValueAsString(qrEntry)
        if (documentType == "GPLX") {
            jdbc.update(
                """UPDATE driver_verification_applications
                   SET revision = ?, gplx_qr_live_scan_json = ?, updated_at = CURRENT_TIMESTAMP
                   WHERE id = ?""",
                nextRevision,
                qrJson,
                row.id,
            )
        } else {
            jdbc.update(
                """UPDATE driver_verification_applications
                   SET revision = ?, qr_live_scan_json = ?, updated_at = CURRENT_TIMESTAMP
                   WHERE id = ?""",
                nextRevision,
                qrJson,
                row.id,
            )
            val refreshed = requireById(row.id)
            syncQrCccdIdHmac(refreshed, buildWorkingDocument(refreshed))
        }
        audit(
            row.id,
            nextRevision,
            actor,
            "QR_LIVE_SCAN",
            mapOf(
                "source" to "QR_LIVE_SCAN",
                "documentType" to documentType,
                "documentSide" to documentSide,
                "attachmentId" to boundAttachmentId,
                "status" to qrStatus,
                "hasConflicts" to summary.conflictFields.isNotEmpty(),
            ),
            review = false,
        )
        val updated = requireById(row.id)
        val result = withWarnings(updated, buildWorkingDocument(updated)).toMutableMap()
        result["qr"] = qrEntry
        return result
    }

    @Transactional
    fun confirmFields(
        actor: AuthUser,
        applicationId: UUID,
        expectedRevision: Long,
        rawDetails: Map<String, Any?>,
    ): Map<String, Any?> {
        val row = requireAccessibleApp(applicationId, actor, review = false, forUpdate = true)
        checkRevision(row, expectedRevision, editable = true)

        val before = row.confirmedFieldsJson?.let(::readMap) ?: emptyMap()
        val validated = validateAndCanonicalizeDetails(rawDetails, before)

        val changed = validated.keys.filter { before[it] != validated[it] }.sorted()
        val targets = requestedChanges(row)
        if (targets != null) {
            val allowedFields = (targets["fields"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
            val disallowed = changed.toSet() - allowedFields
            if (disallowed.isNotEmpty()) {
                throw VerificationWorkflowException(403, "FORBIDDEN", "Chỉ được chỉnh sửa các trường được yêu cầu bổ sung.")
            }
        }

        enforceLockedIdentity(row, validated)

        val nextRevision = expectedRevision + 1L
        val cccdIdHmac = (validated["cccd_id"] as? String)?.trim()?.takeIf { it.matches(Regex("^[0-9]{12}$")) }
            ?.let { CccdQrParser.computeLookupHmac(it, lookupHmacSecret) }
        jdbc.update(
            """UPDATE driver_verification_applications
               SET revision = ?, confirmed_fields = ?, confirmed_at = CURRENT_TIMESTAMP,
                   confirmed_revision = ?, confirmation_current = true,
                   cccd_id_hmac = ?, updated_at = CURRENT_TIMESTAMP
               WHERE id = ?""",
            nextRevision,
            mapper.writeValueAsString(validated),
            nextRevision,
            SqlParameterValue(Types.VARCHAR, cccdIdHmac),
            row.id,
        )
        audit(row.id, nextRevision, actor, "CONFIRM_FIELDS", mapOf("changedFields" to changed), review = false)
        val updated = requireById(row.id)
        return withWarnings(updated, buildWorkingDocument(updated))
    }

    fun faceQuality(
        actor: AuthUser,
        applicationId: UUID,
        expectedRevision: Long,
        faceConsentVersion: String?,
    ): Map<String, Any?> {
        if (faceConsentVersion != FACE_CONSENT_VERSION) {
            throw VerificationWorkflowException(400, "INVALID_REQUEST", "Phiên bản đồng ý không hợp lệ.")
        }
        val faceEnabled = ekycClient?.faceComparisonEnabled == true
        val activeCaps = inTransaction {
            val row = requireAccessibleApp(applicationId, actor, review = false, forUpdate = false)
            checkRevision(row, expectedRevision, editable = true)
            if (faceEnabled) loadActiveCaptures(row.id) else emptyMap()
        }
        if (!faceEnabled) {
            val warnings = DriverVerificationWarnings.build(emptyMap(), expectedRevision)
                .filter { it.evidenceSource == "FACE_COMPARISON" }
            return mapOf(
                "revision" to expectedRevision,
                "status" to "NOT_EVALUATED",
                "verificationWarnings" to warnings,
            )
        }
        val images = linkedMapOf<String, ByteArray>()
        for (slot in DriverVerificationWarnings.FACE_SLOTS) {
            val cap = activeCaps[slot] ?: continue
            runCatching { storage.read(cap.storageKey, cap.byteSize, cap.sha256) }.getOrNull()?.let {
                images[slot] = it
            }
        }
        val resultNode = ekycClient.evaluateFace(images, qualityOnly = true)
        val qualityMap = nodeToStringMap(resultNode.path("quality"))
        val warnings = DriverVerificationWarnings.build(emptyMap(), expectedRevision, faceQuality = qualityMap)
            .filter { it.evidenceSource == "FACE_COMPARISON" }
        val status = if (resultNode.path("reason").asString("") == "QUALITY_ONLY") "QUALITY_CHECKED" else "NOT_EVALUATED"
        return mapOf(
            "revision" to expectedRevision,
            "status" to status,
            "verificationWarnings" to warnings,
        )
    }

    @Transactional
    fun submit(
        actor: AuthUser,
        applicationId: UUID,
        idempotencyKey: String,
        expectedRevision: Long,
        faceConsentVersion: String?,
        warningAcknowledgementRevision: Long?,
    ): Map<String, Any?> {
        if (!idempotencyKey.matches(Regex("^[!-~]{1,128}$"))) {
            throw VerificationWorkflowException(400, "INVALID_REQUEST", "Khóa Idempotency-Key không hợp lệ.")
        }
        val row = requireAccessibleApp(applicationId, actor, review = false, forUpdate = true)

        val priorKey = jdbc.query(
            "SELECT expected_revision, response_json FROM driver_verification_submit_keys WHERE application_id = ? AND idempotency_key = ?",
            { rs, _ -> rs.getLong("expected_revision") to readMap(rs.getString("response_json")) },
            row.id,
            idempotencyKey,
        ).firstOrNull()
        if (priorKey != null) {
            val (priorExpected, savedResponse) = priorKey
            if (priorExpected != expectedRevision ||
                savedResponse["faceConsentVersion"] != faceConsentVersion ||
                (savedResponse["warningAcknowledgementRevision"] as? Number)?.toLong() != warningAcknowledgementRevision
            ) {
                throw VerificationWorkflowException(409, "IDEMPOTENCY_CONFLICT", "Yêu cầu nộp trùng khóa nhưng khác nội dung.")
            }
            return savedResponse
        }

        checkRevision(row, expectedRevision, editable = true)
        val workingDoc = buildWorkingDocument(row)
        @Suppress("UNCHECKED_CAST")
        val capturesMap = workingDoc["captures"] as? Map<String, Any?> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val confirmed = workingDoc["confirmed"] as? Map<String, Any?>
        if (!row.confirmationCurrent || confirmed == null || !capturesMap.keys.containsAll(DriverVerificationWarnings.REQUIRED_SLOTS)) {
            throw VerificationWorkflowException(400, "FIELD_MISSING", "Cần đủ ảnh bắt buộc và xác nhận thông tin trước khi nộp.")
        }
        validateAndCanonicalizeDetails(confirmed, emptyMap())
        enforceLockedIdentity(row, confirmed)

        val targets = requestedChanges(row)
        if (targets != null && row.submissionRevision != null) {
            val previousSnapshot = loadSubmissionSnapshot(row.id, row.submissionRevision)
            @Suppress("UNCHECKED_CAST")
            val prevCaps = previousSnapshot["captures"] as? Map<String, Any?> ?: emptyMap()
            val requiredSlots = (targets["captures"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            for (slot in requiredSlots) {
                val curId = (capturesMap[slot] as? Map<*, *>)?.get("id")
                val oldId = (prevCaps[slot] as? Map<*, *>)?.get("id")
                if (curId == null || curId == oldId) {
                    throw VerificationWorkflowException(400, "FIELD_MISSING", "Cần chụp hoặc tải lại ảnh được yêu cầu bổ sung.")
                }
            }
        }

        val identityCtx = buildIdentityContext(row, confirmed, workingDoc)
        val structuredWarnings = DriverVerificationWarnings.build(
            payload = workingDoc,
            revision = expectedRevision,
            identityContext = identityCtx,
        )
        val requiresReviewCodes = structuredWarnings.filter { it.requiresReview }.map { it.code }.distinct().sorted()
        if (structuredWarnings.any { it.blocksSubmission }) {
            throw VerificationWorkflowException(400, "FIELD_MISSING", "Hồ sơ còn thiếu dữ liệu bắt buộc.")
        }
        if ((requiresReviewCodes.isNotEmpty() || warningAcknowledgementRevision != null) &&
            warningAcknowledgementRevision != expectedRevision
        ) {
            throw VerificationWorkflowException(400, "WARNING_ACK_REQUIRED", "Hãy xem và xác nhận cảnh báo của phiên bản hiện tại.")
        }

        val severeConflicts = structuredWarnings.filter { it.code in SEVERE_IDENTITY_CONFLICT_CODES }
        val applyIdentityHold = severeConflicts.isNotEmpty()
        val holdReason = if (applyIdentityHold) {
            severeConflicts.joinToString("; ") { it.humanReadableMessage }
        } else null

        val nextRevision = expectedRevision + 1L
        val submittedAt = Instant.now()
        val resubmitting = row.status == "NEEDS_MORE_INFO"
        val acknowledgement = if (warningAcknowledgementRevision != null) {
            mapOf("revision" to expectedRevision, "codes" to requiresReviewCodes)
        } else null
        val consentObj = if (faceConsentVersion != null) {
            mapOf("version" to faceConsentVersion, "acceptedAt" to submittedAt.toString())
        } else null

        val snapshot = linkedMapOf<String, Any?>(
            "captures" to capturesMap,
            "proposals" to (workingDoc["proposals"] ?: emptyMap<String, Any?>()),
            "confirmed" to confirmed,
            "confirmationCurrent" to true,
            "revision" to nextRevision,
            "submittedAt" to submittedAt.toString(),
            "warningCodes" to DriverVerificationWarnings.comparisonWarnings(confirmed),
            "warningCount" to requiresReviewCodes.size,
            "verificationWarnings" to structuredWarnings,
            "warningAcknowledgement" to acknowledgement,
            "identityHold" to applyIdentityHold,
        )
        workingDoc["qrLiveScan"]?.let { snapshot["qrLiveScan"] = it }
        if (consentObj != null) snapshot["faceConsent"] = consentObj

        jdbc.update(
            "INSERT INTO driver_verification_submissions (application_id, revision, submitted_at, snapshot_json) VALUES (?, ?, ?, ?)",
            row.id,
            nextRevision,
            Timestamp.from(submittedAt),
            mapper.writeValueAsString(snapshot),
        )
        jdbc.update(
            """UPDATE driver_verification_captures
               SET first_submitted_revision = ?
               WHERE application_id = ? AND retired_at IS NULL AND first_submitted_revision IS NULL""",
            nextRevision,
            row.id,
        )
        jdbc.update(
            """UPDATE driver_verification_applications
               SET status = 'SUBMITTED', revision = ?, submission_revision = ?, submitted_at = ?,
                   resubmission_count = resubmission_count + ?, face_consent = COALESCE(?, face_consent),
                   identity_hold = ?, identity_hold_reason = ?,
                   updated_at = CURRENT_TIMESTAMP
               WHERE id = ?""",
            nextRevision,
            nextRevision,
            Timestamp.from(submittedAt),
            if (resubmitting) 1 else 0,
            SqlParameterValue(Types.VARCHAR, consentObj?.let { mapper.writeValueAsString(it) }),
            applyIdentityHold,
            SqlParameterValue(Types.VARCHAR, holdReason),
            row.id,
        )
        audit(
            row.id,
            nextRevision,
            actor,
            if (resubmitting) "RESUBMIT" else "SUBMIT",
            if (acknowledgement != null) mapOf("warningAcknowledgement" to acknowledgement) else emptyMap(),
            review = false,
        )
        if (applyIdentityHold) {
            audit(
                row.id,
                nextRevision,
                actor,
                "IDENTITY_HOLD_APPLIED",
                mapOf("codes" to severeConflicts.map { it.code }, "reason" to holdReason),
                review = false,
            )
        }

        val preservePriorApproval = row.approvedRevision != null && !applyIdentityHold
        if (!preservePriorApproval) {
            syncProjectionAndDriver(
                ownerUid = row.ownerUid,
                applicationId = row.id,
                revision = nextRevision,
                status = if (applyIdentityHold && row.approvedRevision != null) {
                    VerificationStatus.UNDER_REVIEW
                } else {
                    VerificationStatus.SUBMITTED
                },
                confirmed = confirmed,
                capabilityReview = null,
                reason = holdReason,
                submissionRevision = nextRevision,
                takeDriverOffline = applyIdentityHold,
            )
        }

        val updated = requireById(row.id)
        val result = metadata(updated).toMutableMap()
        if (warningAcknowledgementRevision != null) {
            result["warningAcknowledgementRevision"] = warningAcknowledgementRevision
        }
        if (consentObj != null) {
            result["faceConsentVersion"] = faceConsentVersion
        }
        result["eligibilityStatus"] = buildEligibilitySummary(updated)
        jdbc.update(
            """INSERT INTO driver_verification_submit_keys
               (application_id, idempotency_key, expected_revision, request_fingerprint, response_json)
               VALUES (?, ?, ?, ?, ?)""",
            row.id,
            idempotencyKey,
            expectedRevision,
            "$expectedRevision:${faceConsentVersion ?: ""}:${warningAcknowledgementRevision ?: ""}",
            mapper.writeValueAsString(result),
        )
        return result
    }

    @Transactional(readOnly = true)
    fun reviewQueue(
        actor: AuthUser,
        status: String?,
        applicationId: UUID?,
        limit: Int,
        offset: Int,
    ): Map<String, Any?> {
        requireAdmin(actor)
        val whereParts = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (status != null) {
            whereParts += "a.status = ?"
            args += status
        }
        if (applicationId != null) {
            whereParts += "a.id = ?"
            args += applicationId
        }
        val whereSql = if (whereParts.isEmpty()) "" else "WHERE ${whereParts.joinToString(" AND ")}"
        args += limit + 1
        args += offset
        val rows = jdbc.query(
            """SELECT a.id, a.status, a.revision, a.submission_revision, a.submitted_at,
                      a.resubmission_count, s.snapshot_json
               FROM driver_verification_applications a
               JOIN driver_verification_submissions s
                 ON s.application_id = a.id AND s.revision = a.submission_revision
               $whereSql
               ORDER BY CASE WHEN a.status IN ('SUBMITTED', 'UNDER_REVIEW') THEN 0 ELSE 1 END,
                        a.submitted_at ASC, a.id
               LIMIT ? OFFSET ?""",
            { rs, _ ->
                val snap = readMap(rs.getString("snapshot_json"))
                val wCount = (snap["warningCount"] as? Number)?.toInt() ?: 0
                mapOf(
                    "id" to rs.getObject("id", UUID::class.java).toString(),
                    "status" to rs.getString("status"),
                    "revision" to rs.getLong("revision"),
                    "submissionRevision" to rs.getLong("submission_revision"),
                    "submittedAt" to rs.getTimestamp("submitted_at").toInstant().toString(),
                    "warningCount" to wCount,
                    "resubmissionCount" to rs.getInt("resubmission_count"),
                )
            },
            *args.toTypedArray(),
        )
        return mapOf(
            "items" to rows.take(limit),
            "hasMore" to (rows.size > limit),
        )
    }

    @Transactional
    fun faceComparison(
        actor: AuthUser,
        applicationId: UUID,
        revision: Long,
        submissionRevision: Long,
    ): Any {
        requireAdmin(actor)
        val row = requireAccessibleApp(applicationId, actor, review = true, forUpdate = true)
        if (row.ownerUid == actor.firebaseUid) {
            throw VerificationWorkflowException(403, "FORBIDDEN", "Không thể tự đối chiếu hồ sơ của chính mình.")
        }
        checkRevision(row, revision, editable = false)
        if (submissionRevision != row.submissionRevision) {
            throw VerificationWorkflowException(409, "REVISION_CONFLICT", "Bản nộp đã thay đổi.")
        }
        if (row.status !in setOf("SUBMITTED", "UNDER_REVIEW")) {
            throw VerificationWorkflowException(409, "INVALID_TRANSITION", "Trạng thái hồ sơ không hỗ trợ đối chiếu.")
        }
        val snapshot = loadSubmissionSnapshot(row.id, submissionRevision)
        @Suppress("UNCHECKED_CAST")
        val faceConsent = snapshot["faceConsent"] as? Map<String, Any?>
        val consented = faceConsent?.get("version") == FACE_CONSENT_VERSION
        val client = ekycClient
        if (client == null || !client.faceComparisonEnabled) {
            return client?.disabledFaceResult("DISABLED") ?: mapOf(
                "mode" to "NOT_EVALUATED",
                "reason" to "DISABLED",
                "liveness" to "NOT_EVALUATED",
                "quality" to emptyMap<String, String>(),
                "pairs" to emptyList<Any>(),
            )
        }
        if (!consented) {
            return client.disabledFaceResult("CONSENT_MISSING")
        }
        @Suppress("UNCHECKED_CAST")
        val captures = snapshot["captures"] as? Map<String, Any?> ?: emptyMap()
        val images = linkedMapOf<String, ByteArray>()
        for (slot in DriverVerificationWarnings.FACE_SLOTS) {
            val capIdStr = (captures[slot] as? Map<*, *>)?.get("id") as? String ?: continue
            val capRow = jdbc.query(
                "SELECT * FROM driver_verification_captures WHERE id = ? AND application_id = ?",
                { rs, _ -> rs.toCaptureRow() },
                UUID.fromString(capIdStr),
                row.id,
            ).firstOrNull() ?: continue
            runCatching { storage.read(capRow.storageKey, capRow.byteSize, capRow.sha256) }.getOrNull()?.let {
                images[slot] = it
            }
        }
        return client.evaluateFace(images, qualityOnly = false)
    }

    @Transactional
    fun decision(
        actor: AuthUser,
        applicationId: UUID,
        request: VerificationDecisionRequest,
    ): Map<String, Any?> {
        requireAdmin(actor)
        val row = requireAccessibleApp(applicationId, actor, review = true, forUpdate = true)
        if (row.ownerUid == actor.firebaseUid) {
            throw VerificationWorkflowException(403, "FORBIDDEN", "Không thể tự duyệt hồ sơ của chính mình.")
        }
        checkRevision(row, request.revision, editable = false)
        val targetStatus = request.status.trim().uppercase()
        if (targetStatus !in TRANSITIONS[row.status].orEmpty()) {
            throw VerificationWorkflowException(409, "INVALID_TRANSITION", "Không thể chuyển sang trạng thái $targetStatus từ ${row.status}.")
        }
        val reason = request.reason?.trim()?.takeIf { it.isNotEmpty() }
        if (targetStatus in setOf("NEEDS_MORE_INFO", "REJECTED", "REVOKED") && reason == null) {
            throw VerificationWorkflowException(400, "INVALID_REQUEST", "Cần nhập lý do cụ thể.")
        }
        if (request.capabilityReview != null && targetStatus != "APPROVED") {
            throw VerificationWorkflowException(400, "INVALID_REQUEST", "Chỉ gửi capabilityReview khi phê duyệt.")
        }

        val normalizedFields = request.fields.map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        if (normalizedFields.any { it in setOf("expiry_date", "no_expiry") }) {
            normalizedFields.addAll(listOf("expiry_date", "no_expiry"))
        }
        var requestedTargets: Map<String, Any?>? = null
        if (targetStatus == "NEEDS_MORE_INFO") {
            val cleanCaptures = request.captures.distinct().filter { it in DriverVerificationWarnings.ALL_SLOTS }.sorted()
            if (cleanCaptures.isEmpty() && normalizedFields.isEmpty()) {
                throw VerificationWorkflowException(400, "INVALID_REQUEST", "Chọn ít nhất một ảnh hoặc trường cần bổ sung.")
            }
            requestedTargets = mapOf("captures" to cleanCaptures, "fields" to normalizedFields.sorted())
        } else if (request.captures.isNotEmpty() || normalizedFields.isNotEmpty()) {
            throw VerificationWorkflowException(400, "INVALID_REQUEST", "Không truyền danh sách bổ sung cho quyết định $targetStatus.")
        }

        val submissionRev = row.submissionRevision
            ?: throw VerificationWorkflowException(400, "INVALID_TRANSITION", "Chưa có bản nộp hợp lệ.")
        val snapshot = loadSubmissionSnapshot(row.id, submissionRev)
        @Suppress("UNCHECKED_CAST")
        val confirmed = snapshot["confirmed"] as? Map<String, Any?> ?: emptyMap()
        if (targetStatus == "APPROVED") {
            @Suppress("UNCHECKED_CAST")
            val snapshotFaceStatuses = (snapshot["faceStatuses"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            val warnings = DriverVerificationWarnings.build(
                payload = snapshot,
                revision = request.revision,
                faceStatuses = snapshotFaceStatuses,
                identityContext = buildIdentityContext(row, confirmed, snapshot),
            )
            val blockers = warnings.filter { it.blocksActivation }
            val hardBlockers = blockers.filter { it.code in NON_OVERRIDABLE_BLOCKER_CODES }
            val overridableBlockers = blockers.filter { it.code !in NON_OVERRIDABLE_BLOCKER_CODES }
            if (hardBlockers.isNotEmpty() || (overridableBlockers.isNotEmpty() && !request.allowIdentityOverride)) {
                throw VerificationWorkflowException(
                    409,
                    "VERIFICATION_ACTIVATION_BLOCKED",
                    "Hồ sơ có cảnh báo chặn kích hoạt (${blockers.joinToString(", ") { it.code }}), không thể phê duyệt.",
                )
            }
            if (request.allowIdentityOverride && reason == null) {
                throw VerificationWorkflowException(400, "INVALID_REQUEST", "Cần nhập lý do cụ thể khi thực hiện ngoại lệ điều chỉnh danh tính.")
            }
        }

        val nextRevision = request.revision + 1L
        val confirmationCurrentUpdate = if (targetStatus == "NEEDS_MORE_INFO") false else row.confirmationCurrent

        val nextApprovedRevision = when (targetStatus) {
            "APPROVED" -> nextRevision
            "REVOKED" -> null
            else -> row.approvedRevision
        }
        val nextApprovedSubmissionRev = when (targetStatus) {
            "APPROVED" -> submissionRev
            "REVOKED" -> null
            else -> row.approvedSubmissionRevision
        }
        val nextLockedCccdId = when (targetStatus) {
            "APPROVED" -> (confirmed["cccd_id"] as? String)?.trim()?.takeIf { it.matches(Regex("^[0-9]{12}$")) } ?: row.lockedCccdId
            "REVOKED" -> null
            else -> row.lockedCccdId
        }
        val nextLockedFullName = when (targetStatus) {
            "APPROVED" -> (confirmed["cccd_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: row.lockedFullName
            "REVOKED" -> null
            else -> row.lockedFullName
        }
        val nextLockedDob = when (targetStatus) {
            "APPROVED" -> DriverVerificationWarnings.parseDate(confirmed["cccd_dob"]) ?: row.lockedDob
            "REVOKED" -> null
            else -> row.lockedDob
        }
        val nextVerifiedGplx = when (targetStatus) {
            "APPROVED" -> (confirmed["gplx_number"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: row.verifiedGplxNumber
            "REVOKED" -> null
            else -> row.verifiedGplxNumber
        }
        val nextIdentityHold = when (targetStatus) {
            "APPROVED", "REVOKED" -> false
            else -> row.identityHold
        }
        val nextIdentityHoldReason = when (targetStatus) {
            "APPROVED", "REVOKED" -> null
            else -> row.identityHoldReason
        }

        jdbc.update(
            """UPDATE driver_verification_applications
               SET status = ?, revision = ?, decision_reason = ?, requested_changes = ?,
                   confirmation_current = ?,
                   approved_revision = ?, approved_submission_revision = ?,
                   locked_cccd_id = ?, locked_full_name = ?, locked_dob = ?,
                   verified_gplx_number = ?, identity_hold = ?, identity_hold_reason = ?,
                   updated_at = CURRENT_TIMESTAMP
               WHERE id = ?""",
            targetStatus,
            nextRevision,
            SqlParameterValue(Types.VARCHAR, reason),
            SqlParameterValue(
                Types.VARCHAR,
                requestedTargets?.let { mapper.writeValueAsString(it) }
                    ?: if (targetStatus == "UNDER_REVIEW") row.requestedChangesJson else null,
            ),
            confirmationCurrentUpdate,
            SqlParameterValue(Types.BIGINT, nextApprovedRevision),
            SqlParameterValue(Types.BIGINT, nextApprovedSubmissionRev),
            SqlParameterValue(Types.VARCHAR, nextLockedCccdId),
            SqlParameterValue(Types.VARCHAR, nextLockedFullName),
            SqlParameterValue(Types.DATE, nextLockedDob),
            SqlParameterValue(Types.VARCHAR, nextVerifiedGplx),
            nextIdentityHold,
            SqlParameterValue(Types.VARCHAR, nextIdentityHoldReason),
            row.id,
        )

        val auditMeta = linkedMapOf<String, Any?>(
            "reason" to reason,
            "requestedChanges" to requestedTargets,
        )
        if (request.capabilityReview != null) {
            auditMeta["capabilityGroupIds"] = request.capabilityReview.selectedGroupIds
        }
        if (targetStatus == "APPROVED" && request.allowIdentityOverride) {
            auditMeta["identityOverride"] = true
            audit(row.id, nextRevision, actor, "ADMIN_IDENTITY_OVERRIDE", mapOf("reason" to reason), review = true)
        }
        audit(row.id, nextRevision, actor, targetStatus, auditMeta, review = true)

        val preservePriorApproval = row.approvedRevision != null &&
            !row.identityHold &&
            (targetStatus == "UNDER_REVIEW" ||
                (row.status == "UNDER_REVIEW" && targetStatus in setOf("REJECTED", "NEEDS_MORE_INFO")))

        val effectiveReview = request.capabilityReview?.copy(submissionRevision = submissionRev)
        if (!preservePriorApproval) {
            syncProjectionAndDriver(
                ownerUid = row.ownerUid,
                applicationId = row.id,
                revision = nextRevision,
                status = VerificationStatus.valueOf(targetStatus),
                confirmed = confirmed,
                capabilityReview = effectiveReview,
                reason = reason,
                submissionRevision = submissionRev,
            )
            if (targetStatus == "APPROVED") {
                val approvedGroupIds = runCatching {
                    projection.current(row.ownerUid)?.capabilities?.map { it.groupId }?.toSet()
                }.getOrNull() ?: effectiveReview?.selectedGroupIds?.toSet().orEmpty()
                runCatching { projection.reconcilePreferences(row.ownerUid, nextRevision, approvedGroupIds) }
            }
        } else if (targetStatus in setOf("REJECTED", "NEEDS_MORE_INFO")) {
            notifyDriverStatus(
                ownerUid = row.ownerUid,
                status = if (targetStatus == "REJECTED") "REJECTED" else "PENDING",
                reason = reason,
            )
        }
        return metadata(requireById(row.id))
    }

    private fun syncProjectionAndDriver(
        ownerUid: String,
        applicationId: UUID,
        revision: Long,
        status: VerificationStatus,
        confirmed: Map<String, Any?>,
        capabilityReview: CapabilityReview?,
        reason: String?,
        submissionRevision: Long?,
        takeDriverOffline: Boolean = false,
    ) {
        val entitlements = buildEntitlements(confirmed)
        val cccdExpiresOn = DriverVerificationWarnings.parseDate(confirmed["cccd_expiry_date"])
        projection.upsertDirectProjection(
            ownerUid = ownerUid,
            applicationId = applicationId,
            revision = revision,
            status = status,
            entitlements = entitlements,
            cccdExpiresOn = cccdExpiresOn,
            capabilityReview = capabilityReview,
        )

        val driver = driverRepository.findByFirebaseUid(ownerUid).orElse(null) ?: return
        val lockedDriver = driverRepository.findByIdForUpdate(driver.id).orElse(driver)
        var notifyStatus: String? = null
        when (status) {
            VerificationStatus.APPROVED -> {
                lockedDriver.verificationStatus = "APPROVED"
                lockedDriver.licenseVerified = true
                (confirmed["gplx_number"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    lockedDriver.licenseNumber = it
                }
                val classes = (confirmed["gplx_classes"] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.uppercase() }
                if (!classes.isNullOrEmpty()) {
                    lockedDriver.licenseClass = classes.first().take(10)
                }
                lockedDriver.licenseExpiryDate = if (confirmed["no_expiry"] == true) null
                    else DriverVerificationWarnings.parseDate(confirmed["expiry_date"])
                if (lockedDriver.fullName.isNullOrBlank()) {
                    (confirmed["cccd_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        lockedDriver.fullName = it
                    }
                }
                skillRepository?.findAllForDriver(lockedDriver.id)?.forEach { skill ->
                    skillRepository.verify(skill.id, true)
                }
                driverRepository.save(lockedDriver)
                notifyStatus = "APPROVED"
            }
            VerificationStatus.REJECTED, VerificationStatus.REVOKED -> {
                lockedDriver.verificationStatus = "REJECTED"
                lockedDriver.status = DriverStatus.OFFLINE
                lockedDriver.onlineVehicleType = null
                lockedDriver.licenseVerified = false
                driverRepository.save(lockedDriver)
                afterCommit {
                    runCatching { locationService?.removeDriver(lockedDriver.id) }
                }
                notifyStatus = "REJECTED"
            }
            VerificationStatus.NEEDS_MORE_INFO -> {
                lockedDriver.verificationStatus = "PENDING"
                lockedDriver.status = DriverStatus.OFFLINE
                lockedDriver.onlineVehicleType = null
                driverRepository.save(lockedDriver)
                afterCommit {
                    runCatching { locationService?.removeDriver(lockedDriver.id) }
                }
                notifyStatus = "PENDING"
            }
            VerificationStatus.SUBMITTED -> {
                lockedDriver.verificationStatus = "PENDING"
                if (takeDriverOffline) {
                    lockedDriver.status = DriverStatus.OFFLINE
                    lockedDriver.onlineVehicleType = null
                    afterCommit {
                        runCatching { locationService?.removeDriver(lockedDriver.id) }
                    }
                }
                driverRepository.save(lockedDriver)
            }
            VerificationStatus.UNDER_REVIEW -> {
                if (takeDriverOffline) {
                    lockedDriver.verificationStatus = "PENDING"
                    lockedDriver.status = DriverStatus.OFFLINE
                    lockedDriver.onlineVehicleType = null
                    driverRepository.save(lockedDriver)
                    afterCommit {
                        runCatching { locationService?.removeDriver(lockedDriver.id) }
                    }
                }
            }
        }
        if (notifyStatus != null) {
            notifyDriverStatus(ownerUid = ownerUid, driverId = lockedDriver.id, status = notifyStatus, reason = reason)
        }
    }

    private fun notifyDriverStatus(
        ownerUid: String,
        status: String,
        reason: String?,
        driverId: Long? = null,
    ) {
        val resolvedDriverId = driverId ?: driverRepository.findByFirebaseUid(ownerUid).orElse(null)?.id ?: return
        afterCommit {
            runCatching {
                notificationPort?.send(
                    Recipient.Driver(resolvedDriverId),
                    DriverAccountNotificationPayload(
                        type = PayloadType.DRIVER_VERIFICATION_UPDATED,
                        status = status,
                        rejectionReason = reason,
                    ),
                )
            }.onFailure { ex -> log.warn("Failed to notify driver {}", resolvedDriverId, ex) }
        }
    }

    private fun buildEntitlements(confirmed: Map<String, Any?>): List<LicenseEntitlement> {
        val classes = (confirmed["gplx_classes"] as? List<*>)
            ?.mapNotNull { (it as? String)?.trim()?.uppercase()?.takeIf(String::isNotEmpty) }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        if (classes.isEmpty()) return emptyList()
        val noExpiry = confirmed["no_expiry"] == true
        val expiry = DriverVerificationWarnings.parseDate(confirmed["expiry_date"])
        val alwaysUnlimitedMoto = setOf("A1", "A2", "A3", "A")
        val hasFixedExpiryClass = classes.any { it !in alwaysUnlimitedMoto }
        return classes.map { cls ->
            when {
                noExpiry && expiry == null -> LicenseEntitlement(cls, LicenseValidity.NO_EXPIRY, null)
                !noExpiry && expiry != null && classes.size > 1 && hasFixedExpiryClass && cls in alwaysUnlimitedMoto ->
                    LicenseEntitlement(cls, LicenseValidity.NO_EXPIRY, null)
                !noExpiry && expiry != null -> LicenseEntitlement(cls, LicenseValidity.HAS_EXPIRY, expiry)
                else -> LicenseEntitlement(cls, LicenseValidity.UNKNOWN, null)
            }
        }
    }

    private fun buildLegacyConfirmedPrefill(actor: AuthUser): Map<String, Any?>? {
        val driver = driverRepository.findByFirebaseUid(actor.firebaseUid).orElse(null) ?: return null
        val docs = documentRepository?.findCurrentForDriver(driver.id).orEmpty()
        val cccdDoc = docs.firstOrNull { it.documentType == "CCCD" }
        val licenseDoc = docs.firstOrNull { it.documentType == "DRIVER_LICENSE" }
        val result = linkedMapOf<String, Any?>()
        driver.fullName?.trim()?.takeIf { it.isNotEmpty() }?.let {
            result["cccd_name"] = it
            result["gplx_name"] = it
        }
        cccdDoc?.documentNumber?.trim()?.takeIf { it.matches(Regex("^[0-9]{12}$")) }?.let {
            result["cccd_id"] = it
        }
        val licenseNum = (licenseDoc?.documentNumber ?: driver.licenseNumber)?.trim()?.takeIf { it.isNotEmpty() }
        if (licenseNum != null) result["gplx_number"] = licenseNum
        driver.licenseClass?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let {
            result["gplx_classes"] = listOf(it)
        }
        val expiry = licenseDoc?.expiresAt ?: driver.licenseExpiryDate
        if (expiry != null) {
            result["no_expiry"] = false
            result["expiry_date"] = expiry.toString()
        } else if (driver.licenseClass?.uppercase() in setOf("A1", "A2", "A3", "A", "B1")) {
            result["no_expiry"] = true
            result["expiry_date"] = null
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun validateAndCanonicalizeDetails(
        raw: Map<String, Any?>,
        before: Map<String, Any?>,
    ): Map<String, Any?> {
        fun reqStr(key: String, maxLen: Int): String {
            val v = (raw[key] as? String)?.trim()
                ?: throw VerificationWorkflowException(400, "INVALID_FIELD_FORMAT", "Trường $key không hợp lệ.")
            if (v.isEmpty() || v.length > maxLen) {
                throw VerificationWorkflowException(400, "INVALID_FIELD_FORMAT", "Trường $key vượt quá độ dài cho phép.")
            }
            return v
        }

        val cccdName = reqStr("cccd_name", 150)
        val cccdId = reqStr("cccd_id", 12)
        if (!cccdId.matches(Regex("^[0-9]{12}$"))) {
            throw VerificationWorkflowException(400, "INVALID_FIELD_FORMAT", "Số CCCD cần đủ 12 chữ số.")
        }
        val cccdDob = parseIsoDate(raw["cccd_dob"])
            ?: throw VerificationWorkflowException(400, "INVALID_FIELD_FORMAT", "Ngày sinh CCCD không hợp lệ.")

        val cccdExpiry = if (raw.containsKey("cccd_expiry_date")) {
            val rawVal = raw["cccd_expiry_date"]
            if (rawVal == null || (rawVal is String && rawVal.isBlank())) null
            else (parseIsoDate(rawVal) ?: throw VerificationWorkflowException(400, "INVALID_FIELD_FORMAT", "Hạn CCCD không hợp lệ.")).toString()
        } else {
            before["cccd_expiry_date"] as? String
        }

        val genderRaw = ((raw["cccd_gender"] as? String) ?: "").trim().uppercase()
        if (genderRaw !in setOf("", "MALE", "FEMALE")) {
            throw VerificationWorkflowException(400, "INVALID_FIELD_FORMAT", "Giới tính CCCD không hợp lệ.")
        }

        val gplxNumber = reqStr("gplx_number", 30)
        val gplxName = reqStr("gplx_name", 150)
        val gplxDob = parseIsoDate(raw["gplx_dob"])
            ?: throw VerificationWorkflowException(400, "INVALID_FIELD_FORMAT", "Ngày sinh GPLX không hợp lệ.")

        val rawClasses = raw["gplx_classes"] as? List<*>
            ?: throw VerificationWorkflowException(400, "INVALID_FIELD_FORMAT", "Hạng GPLX không hợp lệ.")
        val classes = rawClasses.mapNotNull { (it as? String)?.trim()?.uppercase() }
        if (classes.isEmpty() || classes.size > 20 || classes.any { !it.matches(Regex("^[A-Z0-9]{1,8}$")) }) {
            throw VerificationWorkflowException(400, "INVALID_FIELD_FORMAT", "Danh sách hạng GPLX không hợp lệ.")
        }

        val noExpiry = raw["no_expiry"] as? Boolean
            ?: throw VerificationWorkflowException(400, "INVALID_FIELD_FORMAT", "Thiếu thông tin no_expiry.")
        val rawExpiry = raw["expiry_date"]
        val expiryDate = if (rawExpiry == null || (rawExpiry is String && rawExpiry.isBlank())) null
        else parseIsoDate(rawExpiry) ?: throw VerificationWorkflowException(400, "INVALID_FIELD_FORMAT", "Ngày hết hạn GPLX không hợp lệ.")

        // Enforce strict invariant: unlimited-expiry GPLX has expiry_date == null; fixed-expiry must have a date.
        if (noExpiry != (expiryDate == null)) {
            throw VerificationWorkflowException(400, "INVALID_FIELD_FORMAT", "Thông tin thời hạn GPLX không nhất quán.")
        }

        val result = linkedMapOf<String, Any?>(
            "cccd_name" to cccdName,
            "cccd_id" to cccdId,
            "cccd_dob" to cccdDob.toString(),
            "cccd_gender" to genderRaw,
            "gplx_number" to gplxNumber,
            "gplx_name" to gplxName,
            "gplx_dob" to gplxDob.toString(),
            "gplx_classes" to classes.distinct(),
            "no_expiry" to noExpiry,
            "expiry_date" to expiryDate?.toString(),
        )
        if (cccdExpiry != null || raw.containsKey("cccd_expiry_date")) {
            result["cccd_expiry_date"] = cccdExpiry
        }
        return result
    }

    private fun parseIsoDate(value: Any?): LocalDate? {
        val text = (value as? String)?.trim() ?: return null
        if (!text.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) return null
        return runCatching { LocalDate.parse(text) }.getOrNull()
    }

    private fun buildWorkingDocument(row: ApplicationRow): Map<String, Any?> {
        val activeCaptures = loadActiveCaptures(row.id)
        val captures = linkedMapOf<String, Any?>()
        val proposals = linkedMapOf<String, Any?>()
        for ((slot, cap) in activeCaptures) {
            captures[slot] = mapOf(
                "id" to cap.id.toString(),
                "mime" to cap.mimeType,
                "quality" to readMap(cap.qualityJson),
            )
            if (!cap.proposalJson.isNullOrBlank()) {
                proposals[slot] = mapOf(
                    "captureId" to cap.id.toString(),
                    "result" to readMap(cap.proposalJson),
                )
            }
        }
        val doc = linkedMapOf<String, Any?>(
            "captures" to captures,
            "proposals" to proposals,
            "confirmed" to row.confirmedFieldsJson?.let(::readMap),
            "confirmationCurrent" to row.confirmationCurrent,
        )
        if (!row.qrLiveScanJson.isNullOrBlank()) {
            doc["qrLiveScan"] = readMap(row.qrLiveScanJson)
        }
        if (!row.gplxQrLiveScanJson.isNullOrBlank()) {
            doc["gplxQrLiveScan"] = readMap(row.gplxQrLiveScanJson)
        }
        return doc
    }

    private fun shouldClearQrLiveScan(
        qrLiveScanJson: String?,
        slot: String,
        oldCaptureId: UUID?,
        docPrefix: String,
    ): Boolean {
        val frontSlot = "${docPrefix}_front"
        val backSlot = "${docPrefix}_back"
        if (qrLiveScanJson.isNullOrBlank() || slot !in setOf(frontSlot, backSlot)) return false
        val live = runCatching { readMap(qrLiveScanJson) }.getOrNull() ?: return false
        val boundId = live["attachmentId"] as? String
        if (oldCaptureId != null && boundId == oldCaptureId.toString()) return true
        val side = if (slot == frontSlot) "FRONT" else "BACK"
        val docSide = live["documentSide"] as? String
        return docSide == null || docSide == side || docSide == "UNKNOWN"
    }

    @Suppress("UNCHECKED_CAST")
    private fun collectActiveOcrFields(
        activeCaptures: Map<String, CaptureRow>,
        slotsPair: List<String>,
    ): Map<String, Any?> {
        val ocrFields = linkedMapOf<String, Any?>()
        for (slot in slotsPair) {
            val rawProposal = activeCaptures[slot]?.proposalJson ?: continue
            val result = runCatching { readMap(rawProposal) }.getOrNull() ?: continue
            val candidates = (result["candidates"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: continue
            for (cand in candidates) {
                if (cand["source"] == "OCR" && cand["parseStatus"] == "parsed" &&
                    cand["validationStatus"] == "valid" && cand["candidateValue"] != null
                ) {
                    val key = cand["fieldKey"] as? String
                    if (!key.isNullOrBlank()) {
                        ocrFields.putIfAbsent(key, cand["candidateValue"])
                    }
                }
            }
        }
        return ocrFields
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractQrCccdId(doc: Map<String, Any?>?): String? {
        if (doc == null) return null
        val captures = doc["captures"] as? Map<String, Any?> ?: emptyMap()
        val proposals = doc["proposals"] as? Map<String, Any?> ?: emptyMap()
        for (slot in listOf("cccd_front", "cccd_back")) {
            val prop = proposals[slot] as? Map<String, Any?> ?: continue
            val curId = (captures[slot] as? Map<*, *>)?.get("id") as? String
            if (curId != null && prop["captureId"] != curId) continue
            val qr = (prop["result"] as? Map<String, Any?>)?.get("qr") as? Map<String, Any?> ?: continue
            if ((qr["status"] as? String) in setOf("DECODED_VALID", "CONFLICT")) {
                val fields = qr["fields"] as? Map<String, Any?> ?: continue
                val id = (fields["id_number"] as? String)?.trim()?.takeIf { it.matches(Regex("^[0-9]{12}$")) }
                if (id != null) return id
            }
        }
        val live = doc["qrLiveScan"] as? Map<String, Any?>
        if (live != null && (live["status"] as? String) in setOf("DECODED_VALID", "CONFLICT")) {
            val fields = live["fields"] as? Map<String, Any?>
            val id = (fields?.get("id_number") as? String)?.trim()?.takeIf { it.matches(Regex("^[0-9]{12}$")) }
            if (id != null) return id
        }
        return null
    }

    private fun syncQrCccdIdHmac(row: ApplicationRow, doc: Map<String, Any?>) {
        val qrId = extractQrCccdId(doc)
        val hmac = qrId?.let { CccdQrParser.computeLookupHmac(it, lookupHmacSecret) }
        jdbc.update(
            "UPDATE driver_verification_applications SET qr_cccd_id_hmac = ? WHERE id = ?",
            SqlParameterValue(Types.VARCHAR, hmac),
            row.id,
        )
    }

    private fun loadActiveCaptures(applicationId: UUID): Map<String, CaptureRow> =
        jdbc.query(
            """SELECT * FROM driver_verification_captures
               WHERE application_id = ? AND retired_at IS NULL""",
            { rs, _ -> rs.toCaptureRow() },
            applicationId,
        ).associateBy { it.slot }

    private fun loadSubmissionSnapshot(applicationId: UUID, revision: Long): Map<String, Any?> {
        val raw = jdbc.query(
            "SELECT snapshot_json FROM driver_verification_submissions WHERE application_id = ? AND revision = ?",
            { rs, _ -> rs.getString("snapshot_json") },
            applicationId,
            revision,
        ).firstOrNull() ?: throw VerificationWorkflowException(404, "NOT_FOUND", "Không tìm thấy bản nộp.")
        return readMap(raw)
    }

    private fun checkUploadRateLimit(ownerUid: String) {
        jdbc.update(
            "DELETE FROM driver_verification_upload_attempts WHERE attempted_at <= CURRENT_TIMESTAMP - interval '1 minute'",
        )
        val count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM driver_verification_upload_attempts WHERE owner_uid = ?",
            Long::class.java,
            ownerUid,
        ) ?: 0L
        if (count >= uploadsPerMinute) {
            throw VerificationWorkflowException(429, "RATE_LIMITED", "Bạn đã tải lên quá nhiều ảnh trong thời gian ngắn.")
        }
        jdbc.update(
            "INSERT INTO driver_verification_upload_attempts (owner_uid, attempted_at) VALUES (?, CURRENT_TIMESTAMP)",
            ownerUid,
        )
    }

    private fun requireAdmin(actor: AuthUser) {
        if (actor.role != Role.ADMIN) {
            throw VerificationWorkflowException(403, "FORBIDDEN", "Tài khoản chưa có quyền duyệt hồ sơ eKYC.")
        }
    }

    private fun requireAccessibleApp(
        id: UUID,
        actor: AuthUser,
        review: Boolean,
        forUpdate: Boolean,
    ): ApplicationRow {
        if (review) requireAdmin(actor)
        val sql = "SELECT * FROM driver_verification_applications WHERE id = ?" + if (forUpdate) " FOR UPDATE" else ""
        val row = jdbc.query(sql, { rs, _ -> rs.toAppRow() }, id).firstOrNull()
            ?: throw VerificationWorkflowException(404, "NOT_FOUND", "Không tìm thấy hồ sơ xác minh.")
        if (!review && row.ownerUid != actor.firebaseUid) {
            throw VerificationWorkflowException(404, "NOT_FOUND", "Không tìm thấy hồ sơ xác minh.")
        }
        if (review && row.submissionRevision == null) {
            throw VerificationWorkflowException(404, "NOT_FOUND", "Hồ sơ chưa được nộp.")
        }
        return row
    }

    private fun findByOwner(ownerUid: String): ApplicationRow? =
        jdbc.query(
            "SELECT * FROM driver_verification_applications WHERE owner_uid = ?",
            { rs, _ -> rs.toAppRow() },
            ownerUid,
        ).firstOrNull()

    private fun requireById(id: UUID): ApplicationRow =
        jdbc.query(
            "SELECT * FROM driver_verification_applications WHERE id = ?",
            { rs, _ -> rs.toAppRow() },
            id,
        ).single()

    private fun checkRevision(row: ApplicationRow, expected: Long, editable: Boolean) {
        if (row.revision != expected) {
            throw VerificationWorkflowException(409, "REVISION_CONFLICT", "Hồ sơ đã thay đổi, hãy làm mới trạng thái.")
        }
        if (editable && row.status !in EDITABLE_STATES) {
            throw VerificationWorkflowException(409, "INVALID_TRANSITION", "Hồ sơ đang ở trạng thái ${row.status}, không thể chỉnh sửa.")
        }
    }

    private fun requestedChanges(row: ApplicationRow): Map<String, Any?>? {
        if (row.status != "NEEDS_MORE_INFO") return null
        return row.requestedChangesJson?.let(::readMap)
    }

    private fun enforceRequestedSlot(row: ApplicationRow, slot: String) {
        val targets = requestedChanges(row) ?: return
        val allowed = (targets["captures"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        if (slot !in allowed) {
            throw VerificationWorkflowException(403, "FORBIDDEN", "Không được phép thay đổi ảnh ở mục này.")
        }
    }

    private fun enforceLockedIdentity(row: ApplicationRow, confirmed: Map<String, Any?>) {
        if (row.lockedCccdId != null) {
            val submittedCccd = (confirmed["cccd_id"] as? String)?.trim()
            if (submittedCccd != row.lockedCccdId) {
                log.warn("Blocked locked CCCD change on app {} owner {}", row.id, row.ownerUid)
                throw VerificationWorkflowException(
                    409,
                    "IDENTITY_LOCKED",
                    "Số định danh CCCD khác với danh tính đã xác minh. Thông tin định danh đã xác minh không thể tự ý thay đổi.",
                )
            }
        }
        if (row.lockedFullName != null) {
            val submittedName = confirmed["cccd_name"] as? String
            if (DriverVerificationWarnings.nameComparison(submittedName, row.lockedFullName) == "NAME_MISMATCH") {
                log.warn("Blocked locked CCCD name mismatch on app {} owner {}", row.id, row.ownerUid)
                throw VerificationWorkflowException(
                    409,
                    "IDENTITY_LOCKED",
                    "Họ tên CCCD không khớp với danh tính đã xác minh của tài khoản.",
                )
            }
        }
        if (row.lockedDob != null) {
            val submittedDob = DriverVerificationWarnings.parseDate(confirmed["cccd_dob"])
            if (submittedDob != null && submittedDob != row.lockedDob) {
                log.warn("Blocked locked CCCD DOB mismatch on app {} owner {}", row.id, row.ownerUid)
                throw VerificationWorkflowException(
                    409,
                    "IDENTITY_LOCKED",
                    "Ngày sinh CCCD không khớp với danh tính đã xác minh của tài khoản.",
                )
            }
        }
    }

    private fun buildIdentityContext(
        row: ApplicationRow,
        confirmed: Map<String, Any?>?,
        doc: Map<String, Any?>? = null,
    ): IdentityVerificationContext {
        val cccdId = (confirmed?.get("cccd_id") as? String)?.trim()
            ?: extractQrCccdId(doc)
        val gplxNum = (confirmed?.get("gplx_number") as? String)?.trim()
        @Suppress("UNCHECKED_CAST")
        val prevConfirmed = row.approvedSubmissionRevision?.let { rev ->
            runCatching { loadSubmissionSnapshot(row.id, rev)["confirmed"] as? Map<String, Any?> }.getOrNull()
        }
        return IdentityVerificationContext(
            lockedCccdId = row.lockedCccdId,
            lockedFullName = row.lockedFullName,
            lockedDob = row.lockedDob,
            verifiedGplxNumber = row.verifiedGplxNumber,
            previousApprovedConfirmed = prevConfirmed,
            duplicateCccdAccount = hasDuplicateCccdAcrossAccounts(row.ownerUid, cccdId),
            duplicateGplxAccount = hasDuplicateGplxAcrossAccounts(row.ownerUid, gplxNum),
        )
    }

    private fun hasDuplicateCccdAcrossAccounts(ownerUid: String, cccdId: String?): Boolean {
        val clean = cccdId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val cccdHmac = CccdQrParser.computeLookupHmac(clean, lookupHmacSecret)
        val appDup = runCatching {
            jdbc.query(
                """SELECT locked_cccd_id, status, confirmed_fields, cccd_id_hmac, qr_cccd_id_hmac
                   FROM driver_verification_applications
                   WHERE owner_uid <> ?
                     AND (locked_cccd_id = ?
                          OR cccd_id_hmac = ?
                          OR qr_cccd_id_hmac = ?
                          OR status IN ('APPROVED', 'SUBMITTED', 'UNDER_REVIEW', 'NEEDS_MORE_INFO'))""",
                { rs, _ ->
                    listOf(
                        rs.getString("locked_cccd_id"),
                        rs.getString("confirmed_fields"),
                        rs.getString("cccd_id_hmac"),
                        rs.getString("qr_cccd_id_hmac"),
                    )
                },
                ownerUid,
                clean,
                cccdHmac,
                cccdHmac,
            ).any { values ->
                val lockedId = values[0]
                val confirmedJson = values[1]
                val storedHmac = values[2]
                val storedQrHmac = values[3]
                if (lockedId == clean || storedHmac == cccdHmac || storedQrHmac == cccdHmac) return@any true
                if (confirmedJson.isNullOrBlank()) return@any false
                (readMap(confirmedJson)["cccd_id"] as? String)?.trim() == clean
            }
        }.getOrDefault(false)
        if (appDup) return true

        return runCatching {
            val legacyCount = jdbc.queryForObject(
                """SELECT COUNT(*) FROM driver_documents doc
                   JOIN drivers d ON d.id = doc.driver_id
                   WHERE d.firebase_uid <> ? AND d.deleted_at IS NULL
                     AND doc.is_current = true AND doc.verification_status = 'APPROVED'
                     AND doc.document_type = 'CCCD' AND TRIM(doc.document_number) = ?""",
                Long::class.java,
                ownerUid,
                clean,
            ) ?: 0L
            legacyCount > 0L
        }.getOrDefault(false)
    }

    private fun hasDuplicateGplxAcrossAccounts(ownerUid: String, gplxNumber: String?): Boolean {
        val clean = gplxNumber?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return false
        val appDup = runCatching {
            jdbc.query(
                """SELECT verified_gplx_number, status, confirmed_fields
                   FROM driver_verification_applications
                   WHERE owner_uid <> ?
                     AND (UPPER(TRIM(verified_gplx_number)) = ? OR status IN ('APPROVED', 'SUBMITTED', 'UNDER_REVIEW'))""",
                { rs, _ ->
                    Triple(
                        rs.getString("verified_gplx_number"),
                        rs.getString("status"),
                        rs.getString("confirmed_fields"),
                    )
                },
                ownerUid,
                clean,
            ).any { (verifiedGplx, _, confirmedJson) ->
                if (verifiedGplx?.trim()?.uppercase() == clean) return@any true
                if (confirmedJson.isNullOrBlank()) return@any false
                (readMap(confirmedJson)["gplx_number"] as? String)?.trim()?.uppercase() == clean
            }
        }.getOrDefault(false)
        if (appDup) return true

        return runCatching {
            val legacyCount = jdbc.queryForObject(
                """SELECT COUNT(*) FROM driver_documents doc
                   JOIN drivers d ON d.id = doc.driver_id
                   WHERE d.firebase_uid <> ? AND d.deleted_at IS NULL
                     AND doc.is_current = true AND doc.verification_status = 'APPROVED'
                     AND doc.document_type = 'DRIVER_LICENSE' AND UPPER(TRIM(doc.document_number)) = ?""",
                Long::class.java,
                ownerUid,
                clean,
            ) ?: 0L
            legacyCount > 0L
        }.getOrDefault(false)
    }

    private fun buildEligibilitySummary(row: ApplicationRow): Map<String, Any?> {
        val proj = runCatching { projection.current(row.ownerUid) }.getOrNull()
        val today = capabilityToday()
        val activeGroups = proj?.effectiveGroups(today).orEmpty().sorted()
        val approvedGroupIds = proj?.capabilities?.map { it.groupId }?.distinct()?.sorted().orEmpty()
        val restrictionReason = when {
            row.identityHold -> "IDENTITY_UNDER_REVIEW"
            proj == null -> null
            proj.status != VerificationStatus.APPROVED -> "VERIFICATION_NOT_APPROVED"
            proj.cccdExpiresOn?.isBefore(today) == true -> "CCCD_EXPIRED"
            proj.capabilities.isNotEmpty() && proj.capabilities.none { it.valid(today) } -> "GPLX_EXPIRED"
            else -> null
        }
        return linkedMapOf(
            "hasActiveApproval" to (proj?.status == VerificationStatus.APPROVED),
            "canReceiveRides" to (proj?.status == VerificationStatus.APPROVED && restrictionReason == null && activeGroups.isNotEmpty()),
            "approvedGroupIds" to approvedGroupIds,
            "activeEnabledGroupIds" to activeGroups,
            "restrictionReason" to restrictionReason,
        )
    }

    private fun maskCccdId(cccdId: String?): String? {
        val clean = cccdId?.trim()?.takeIf { it.length >= 4 } ?: return null
        return "*".repeat(clean.length - 4) + clean.takeLast(4)
    }

    private fun metadata(row: ApplicationRow, includeRawCccdId: Boolean = false): Map<String, Any?> {
        val identityLocked = row.lockedCccdId != null || row.lockedFullName != null || row.lockedDob != null
        val lockedIdentity = if (identityLocked) {
            linkedMapOf<String, Any?>(
                "maskedCccdId" to maskCccdId(row.lockedCccdId),
                "fullName" to row.lockedFullName,
                "dob" to row.lockedDob?.toString(),
                "verifiedGplxNumber" to row.verifiedGplxNumber,
            ).apply {
                if (includeRawCccdId) put("cccdId", row.lockedCccdId)
            }
        } else null

        return linkedMapOf(
            "id" to row.id.toString(),
            "status" to row.status,
            "revision" to row.revision,
            "submissionRevision" to row.submissionRevision,
            "approvedRevision" to row.approvedRevision,
            "approvedSubmissionRevision" to row.approvedSubmissionRevision,
            "identityLocked" to identityLocked,
            "lockedIdentity" to lockedIdentity,
            "identityHold" to row.identityHold,
            "identityHoldReason" to row.identityHoldReason,
            "reason" to row.reason,
            "updatedAt" to row.updatedAt.toString(),
        )
    }

    private fun withWarnings(row: ApplicationRow, doc: Map<String, Any?>): Map<String, Any?> {
        val result = metadata(row).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val confirmed = doc["confirmed"] as? Map<String, Any?>
        result["verificationWarnings"] = DriverVerificationWarnings.build(
            payload = doc,
            revision = row.revision,
            identityContext = buildIdentityContext(row, confirmed, doc),
        )
        return result
    }

    private fun audit(
        applicationId: UUID,
        revision: Long,
        actor: AuthUser,
        action: String,
        metadata: Map<String, Any?>,
        review: Boolean,
    ) {
        jdbc.update(
            """INSERT INTO driver_verification_audit
               (application_id, revision, actor_uid, actor_role, action, metadata_json, created_at)
               VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)""",
            applicationId,
            revision,
            actor.firebaseUid,
            if (review) "admin" else "driver",
            action,
            mapper.writeValueAsString(metadata),
        )
    }

    private fun readMap(json: String): Map<String, Any?> {
        val type = mapper.typeFactory.constructMapType(LinkedHashMap::class.java, String::class.java, Any::class.java)
        return mapper.readValue(json, type)
    }

    private fun nodeToStringMap(node: JsonNode): Map<String, String> {
        if (!node.isObject) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (entry in node.properties()) {
            if (entry.value.isString) result[entry.key] = entry.value.asString()
        }
        return result
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }

    private fun ResultSet.toAppRow() = ApplicationRow(
        id = getObject("id", UUID::class.java),
        ownerUid = getString("owner_uid"),
        status = getString("status"),
        revision = getLong("revision"),
        confirmedFieldsJson = getString("confirmed_fields"),
        confirmationCurrent = getBoolean("confirmation_current"),
        submissionRevision = getObject("submission_revision", Long::class.javaObjectType),
        submittedAt = getTimestamp("submitted_at")?.toInstant(),
        resubmissionCount = getInt("resubmission_count"),
        reason = getString("decision_reason"),
        requestedChangesJson = getString("requested_changes"),
        faceConsentJson = getString("face_consent"),
        updatedAt = getTimestamp("updated_at").toInstant(),
        approvedRevision = getObject("approved_revision", Long::class.javaObjectType),
        approvedSubmissionRevision = getObject("approved_submission_revision", Long::class.javaObjectType),
        lockedCccdId = getString("locked_cccd_id"),
        lockedFullName = getString("locked_full_name"),
        lockedDob = getObject("locked_dob", LocalDate::class.java),
        verifiedGplxNumber = getString("verified_gplx_number"),
        identityHold = getBoolean("identity_hold"),
        identityHoldReason = getString("identity_hold_reason"),
        qrLiveScanJson = runCatching { getString("qr_live_scan_json") }.getOrNull(),
        gplxQrLiveScanJson = runCatching { getString("gplx_qr_live_scan_json") }.getOrNull(),
    )

    private fun ResultSet.toCaptureRow() = CaptureRow(
        id = getObject("id", UUID::class.java),
        applicationId = getObject("application_id", UUID::class.java),
        slot = getString("slot"),
        storageKey = getString("storage_key"),
        mimeType = getString("mime_type"),
        byteSize = getInt("byte_size"),
        sha256 = getString("sha256"),
        qualityJson = getString("quality_json"),
        proposalJson = getString("proposal_json"),
        capturedRevision = getLong("captured_revision"),
    )
}
