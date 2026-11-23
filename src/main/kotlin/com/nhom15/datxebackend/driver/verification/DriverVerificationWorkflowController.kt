package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.api.ApiError
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.util.UUID

data class RevisionBody(val revision: Long)

data class QrScanBody(
    val revision: Long,
    val rawPayload: String,
    val documentSide: String? = null,
    val documentType: String? = null,
    val attachmentId: UUID? = null,
)

data class ConfirmationBody(
    val revision: Long,
    val details: Map<String, Any?>,
)

data class FaceQualityBody(
    val revision: Long,
    val faceConsentVersion: String?,
)

data class FaceComparisonBody(
    val revision: Long,
    val submissionRevision: Long,
)

data class SubmitBody(
    val revision: Long,
    val faceConsentVersion: String? = null,
    val warningAcknowledgementRevision: Long? = null,
)

@RestController
class DriverVerificationWorkflowController(
    private val service: DriverVerificationWorkflowService,
) {
    private fun ok(data: Any?): Map<String, Any?> = mapOf(
        "success" to true,
        "data" to data,
    )

    @ExceptionHandler(VerificationWorkflowException::class)
    fun handleWorkflowError(ex: VerificationWorkflowException): ResponseEntity<Map<String, Any?>> =
        error(ex.httpStatus, ex.errorCode, ex.message)

    @ExceptionHandler(AppException::class)
    fun handleAppError(ex: AppException): ResponseEntity<Map<String, Any?>> =
        error(ex.httpStatus.value(), ex.errorCode.name, ex.message ?: ex.errorCode.defaultMessage, ex.details)

    private fun error(status: Int, code: String, message: String, details: Map<String, String>? = null):
        ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(status).body(
            mapOf(
                "success" to false,
                "errorCode" to code,
                "error_code" to code,
                "message" to message,
                "error" to ApiError(code, message, details),
                "status" to status,
            ),
        )

    @GetMapping("/ekyc/driver-verification/v1/me", "/api/driver/verification/v1/me")
    @PreAuthorize("hasAnyRole('DRIVER','ADMIN')")
    fun me(@CurrentUser actor: AuthUser): Map<String, Any?> = ok(service.me(actor))

    @PostMapping("/ekyc/driver-verification/v1/applications", "/api/driver/verification/v1/applications")
    @PreAuthorize("hasRole('DRIVER')")
    fun create(@CurrentUser actor: AuthUser): Map<String, Any?> = ok(service.create(actor))

    @GetMapping("/ekyc/driver-verification/v1/applications", "/api/driver/verification/v1/applications")
    @PreAuthorize("hasRole('DRIVER')")
    fun listMine(@CurrentUser actor: AuthUser): Map<String, Any?> = ok(service.list(actor, 1, 0, review = false))

    @GetMapping("/ekyc/driver-verification/v1/applications/{applicationId}", "/api/driver/verification/v1/applications/{applicationId}")
    @PreAuthorize("hasRole('DRIVER')")
    fun detail(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @RequestParam(required = false) snapshot: Long?,
    ): Map<String, Any?> = ok(service.detail(actor, applicationId, snapshotRevision = snapshot, review = false))

    @PutMapping(
        "/ekyc/driver-verification/v1/applications/{applicationId}/captures/{slot}",
        "/api/driver/verification/v1/applications/{applicationId}/captures/{slot}",
    )
    @PreAuthorize("hasRole('DRIVER')")
    fun replaceCapture(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @PathVariable slot: String,
        @RequestParam revision: Long,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val mime = request.contentType?.substringBefore(';')?.trim()?.lowercase() ?: ""
        if (mime !in setOf("image/jpeg", "image/png", "image/webp")) {
            throw VerificationWorkflowException(400, "INVALID_IMAGE", "Định dạng ảnh không được hỗ trợ.")
        }
        if (request.contentLengthLong > 8_000_000L) {
            throw VerificationWorkflowException(413, "IMAGE_TOO_LARGE", "Ảnh vượt quá giới hạn 8 MB.")
        }
        val bytes = try {
            request.inputStream.readNBytes(8_000_001)
        } catch (_: IOException) {
            throw VerificationWorkflowException(400, "INVALID_REQUEST", "Không đọc được dữ liệu ảnh gửi lên.")
        }
        if (bytes.size > 8_000_000) {
            throw VerificationWorkflowException(413, "IMAGE_TOO_LARGE", "Ảnh vượt quá giới hạn 8 MB.")
        }
        return ok(service.replaceCapture(actor, applicationId, slot, revision, mime, bytes))
    }

    @GetMapping(
        "/ekyc/driver-verification/v1/applications/{applicationId}/captures/{slot}",
        "/api/driver/verification/v1/applications/{applicationId}/captures/{slot}",
    )
    @PreAuthorize("hasRole('DRIVER')")
    fun capturePreview(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @PathVariable slot: String,
        @RequestParam(required = false) snapshot: Long?,
        response: HttpServletResponse,
    ): ResponseEntity<ByteArray> {
        val (mime, bytes) = service.readCaptureBytes(actor, applicationId, slot, review = false, snapshotRevision = snapshot)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
            .contentType(MediaType.parseMediaType(mime))
            .body(bytes)
    }

    @PostMapping(
        "/ekyc/driver-verification/v1/applications/{applicationId}/extract/{slot}",
        "/api/driver/verification/v1/applications/{applicationId}/extract/{slot}",
    )
    @PreAuthorize("hasRole('DRIVER')")
    fun extract(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @PathVariable slot: String,
        @RequestBody body: RevisionBody,
    ): Map<String, Any?> = ok(service.extractSlot(actor, applicationId, slot, body.revision))

    @PostMapping(
        "/ekyc/driver-verification/v1/applications/{applicationId}/qr-scan",
        "/api/driver/verification/v1/applications/{applicationId}/qr-scan",
    )
    @PreAuthorize("hasRole('DRIVER')")
    fun qrScan(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @RequestBody body: QrScanBody,
    ): Map<String, Any?> = ok(service.submitQrLiveScan(actor, applicationId, body))

    @PutMapping(
        "/ekyc/driver-verification/v1/applications/{applicationId}/confirmation",
        "/api/driver/verification/v1/applications/{applicationId}/confirmation",
    )
    @PreAuthorize("hasRole('DRIVER')")
    fun confirm(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @RequestBody body: ConfirmationBody,
    ): Map<String, Any?> = ok(service.confirmFields(actor, applicationId, body.revision, body.details))

    @PostMapping(
        "/ekyc/driver-verification/v1/applications/{applicationId}/face-quality",
        "/api/driver/verification/v1/applications/{applicationId}/face-quality",
    )
    @PreAuthorize("hasRole('DRIVER')")
    fun faceQuality(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @RequestBody body: FaceQualityBody,
    ): Map<String, Any?> = ok(service.faceQuality(actor, applicationId, body.revision, body.faceConsentVersion))

    @PostMapping(
        "/ekyc/driver-verification/v1/applications/{applicationId}/submit",
        "/api/driver/verification/v1/applications/{applicationId}/submit",
    )
    @PreAuthorize("hasRole('DRIVER')")
    fun submit(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: SubmitBody,
    ): Map<String, Any?> = ok(
        service.submit(
            actor = actor,
            applicationId = applicationId,
            idempotencyKey = idempotencyKey,
            expectedRevision = body.revision,
            faceConsentVersion = body.faceConsentVersion,
            warningAcknowledgementRevision = body.warningAcknowledgementRevision,
        ),
    )

    @PostMapping(
        "/ekyc/driver-verification/v1/applications/{applicationId}/reverify",
        "/api/driver/verification/v1/applications/{applicationId}/reverify",
    )
    @PreAuthorize("hasRole('DRIVER')")
    fun reverify(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @RequestBody body: RevisionBody,
    ): Map<String, Any?> = ok(service.reverify(actor, applicationId, body.revision))

    @PostMapping(
        "/ekyc/driver-verification/v1/applications/{applicationId}/cancel-reverify",
        "/api/driver/verification/v1/applications/{applicationId}/cancel-reverify",
    )
    @PreAuthorize("hasRole('DRIVER')")
    fun cancelReverify(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @RequestBody body: RevisionBody,
    ): Map<String, Any?> = ok(service.cancelReverify(actor, applicationId, body.revision))

    @GetMapping("/ekyc/driver-verification/v1/admin/applications", "/api/admin/verification/v1/applications")
    @PreAuthorize("hasRole('ADMIN')")
    fun adminList(
        @CurrentUser actor: AuthUser,
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): Map<String, Any?> = ok(service.list(actor, limit.coerceIn(1, 100), offset.coerceAtLeast(0), review = true))

    @GetMapping("/ekyc/driver-verification/v1/admin/review-queue", "/api/admin/verification/v1/review-queue")
    @PreAuthorize("hasRole('ADMIN')")
    fun reviewQueue(
        @CurrentUser actor: AuthUser,
        @RequestParam(defaultValue = "25") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(name = "application_id", required = false) applicationId: UUID?,
    ): Map<String, Any?> = ok(
        service.reviewQueue(
            actor = actor,
            status = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            applicationId = applicationId,
            limit = limit.coerceIn(1, 100),
            offset = offset.coerceAtLeast(0),
        ),
    )

    @GetMapping(
        "/ekyc/driver-verification/v1/admin/applications/{applicationId}",
        "/api/admin/verification/v1/applications/{applicationId}",
    )
    @PreAuthorize("hasRole('ADMIN')")
    fun adminDetail(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @RequestParam(required = false) snapshot: Long?,
    ): Map<String, Any?> = ok(service.detail(actor, applicationId, snapshotRevision = snapshot, review = true))

    @GetMapping(
        "/ekyc/driver-verification/v1/admin/applications/{applicationId}/captures/{slot}",
        "/api/admin/verification/v1/applications/{applicationId}/captures/{slot}",
    )
    @PreAuthorize("hasRole('ADMIN')")
    fun adminCapture(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @PathVariable slot: String,
        @RequestParam(required = false) snapshot: Long?,
    ): ResponseEntity<ByteArray> {
        val (mime, bytes) = service.readCaptureBytes(actor, applicationId, slot, review = true, snapshotRevision = snapshot)
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
            .contentType(MediaType.parseMediaType(mime))
            .body(bytes)
    }

    @PostMapping(
        "/ekyc/driver-verification/v1/admin/applications/{applicationId}/face-comparison",
        "/api/admin/verification/v1/applications/{applicationId}/face-comparison",
    )
    @PreAuthorize("hasRole('ADMIN')")
    fun faceComparison(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @RequestBody body: FaceComparisonBody,
    ): Map<String, Any?> = ok(service.faceComparison(actor, applicationId, body.revision, body.submissionRevision))

    @PostMapping(
        "/ekyc/driver-verification/v1/admin/applications/{applicationId}/decision",
        "/api/admin/verification/v1/applications/{applicationId}/decision",
    )
    @PreAuthorize("hasRole('ADMIN')")
    fun decision(
        @CurrentUser actor: AuthUser,
        @PathVariable applicationId: UUID,
        @RequestBody body: VerificationDecisionRequest,
    ): Map<String, Any?> {
        if (actor.role != Role.ADMIN) {
            throw VerificationWorkflowException(403, "FORBIDDEN", "Tài khoản chưa có quyền duyệt hồ sơ eKYC.")
        }
        return ok(service.decision(actor, applicationId, body))
    }
}
