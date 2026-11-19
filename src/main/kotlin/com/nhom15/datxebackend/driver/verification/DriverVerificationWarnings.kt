package com.nhom15.datxebackend.driver.verification

import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId

data class IdentityVerificationContext(
    val lockedCccdId: String? = null,
    val lockedFullName: String? = null,
    val lockedDob: LocalDate? = null,
    val verifiedGplxNumber: String? = null,
    val previousApprovedConfirmed: Map<String, Any?>? = null,
    val duplicateCccdAccount: Boolean = false,
    val duplicateGplxAccount: Boolean = false,
)

data class VerificationWarningDto(
    val code: String,
    val severity: String,
    val documentType: String?,
    val slot: String?,
    val fieldKey: String?,
    val humanReadableMessage: String,
    val recommendedAction: String,
    val evidenceSource: String,
    val documentRevision: Long,
    val requiresReview: Boolean,
    val blocksSubmission: Boolean,
    val blocksActivation: Boolean,
)

object DriverVerificationWarnings {
    val ALL_SLOTS = setOf("cccd_front", "cccd_back", "gplx_front", "gplx_back", "selfie")
    val REQUIRED_SLOTS = listOf("cccd_front", "cccd_back", "gplx_front", "gplx_back", "selfie")
    val FACE_SLOTS = setOf("cccd_front", "gplx_front", "selfie")

    val LICENSE_CLASSES = setOf(
        "A1", "A2", "A3", "A4", "A",
        "B1", "B2", "B", "BE",
        "C1", "C", "C1E", "CE",
        "D1", "D2", "D", "D1E", "D2E", "DE",
        "E", "FB2", "FC", "FD", "FE",
    )

    private val TEXT = mapOf(
        "IMAGE_BLURRY" to "Ảnh có dấu hiệu mờ.",
        "IMAGE_GLARE" to "Ảnh có vùng lóa.",
        "IMAGE_TOO_SMALL" to "Ảnh có độ phân giải thấp.",
        "CARD_CROPPED" to "Có thể ảnh đã cắt mất một phần thẻ.",
        "CARD_NOT_FOUND" to "Chưa xác định được vùng thẻ.",
        "OCR_INCOMPLETE" to "Lượt đọc chữ chưa lấy đủ thông tin.",
        "QR_OCR_CONFLICT" to "Thông tin trích xuất từ các nguồn chưa khớp.",
        "QR_OCR_NAME_DIACRITIC_MISMATCH" to "Họ tên giữa mã QR và ảnh chữ đọc được chỉ khác dấu tiếng Việt.",
        "QR_OCR_INSUFFICIENT_EVIDENCE" to "Chưa đủ dữ liệu OCR để đối chiếu với mã QR.",
        "QR_LIVE_SCAN_UNBOUND" to "Mã QR quét trực tiếp chưa chứng minh thuộc ảnh giấy tờ đã tải lên.",
        "NAME_DIACRITIC_MISMATCH" to "Họ tên giữa hai giấy tờ khác dấu.",
        "NAME_MISMATCH" to "Họ tên giữa hai giấy tờ khác nhau.",
        "DOB_MISMATCH" to "Ngày sinh giữa hai giấy tờ khác nhau.",
        "CCCD_EXPIRED" to "Ngày hết hạn căn cước đã qua.",
        "CCCD_EXPIRY_UNKNOWN" to "Chưa xác định được thời hạn căn cước.",
        "GPLX_EXPIRED" to "Ngày hết hạn GPLX đã qua.",
        "GPLX_EXPIRY_UNKNOWN" to "Chưa xác định được thời hạn GPLX.",
        "GPLX_CLASS_UNKNOWN" to "Có hạng GPLX chưa xác định.",
        "GPLX_RESTRICTION_UNKNOWN" to "Chưa xác định được hạn chế trên GPLX.",
        "FACE_NOT_EVALUATED" to "Chưa thực hiện đối chiếu khuôn mặt.",
        "FACE_INSUFFICIENT_QUALITY" to "Ảnh chưa đủ chất lượng để đối chiếu khuôn mặt.",
        "FACE_POSSIBLE_MISMATCH" to "Có dấu hiệu không tương đồng giữa các ảnh chân dung.",
        "DOCUMENT_REQUIRED" to "Còn thiếu ảnh bắt buộc.",
        "LOCKED_CCCD_ID_CHANGED" to "Số định danh CCCD khác với danh tính đã xác minh. Không thể tự ý thay đổi danh tính.",
        "LOCKED_IDENTITY_NAME_MISMATCH" to "Họ tên trên giấy tờ không khớp với danh tính đã xác minh của tài khoản.",
        "LOCKED_IDENTITY_DOB_MISMATCH" to "Ngày sinh trên giấy tờ không khớp với danh tính đã xác minh của tài khoản.",
        "GPLX_HOLDER_MISMATCH" to "GPLX mang họ tên hoặc ngày sinh của người khác với danh tính tài xế.",
        "DUPLICATE_CCCD_ACROSS_ACCOUNTS" to "Số định danh CCCD này đã được xác minh hoặc sử dụng bởi một tài khoản tài xế khác.",
        "DUPLICATE_GPLX_ACROSS_ACCOUNTS" to "Số GPLX này đã được xác minh hoặc sử dụng bởi một tài khoản tài xế khác.",
        "CCCD_RENEWAL_REQUIRES_REVIEW" to "Cập nhật ngày hết hạn CCCD mới, cần quản trị viên xét duyệt lại.",
        "GPLX_UPDATED_REQUIRES_REVIEW" to "Cập nhật số GPLX hoặc nâng hạng GPLX mới, cần quản trị viên xét duyệt lại.",
    )

    val FACE_QUALITY_MESSAGES = mapOf(
        "NO_FACE" to "Chưa tìm thấy khuôn mặt trong ảnh này.",
        "MULTIPLE_FACES" to "Ảnh có nhiều khuôn mặt.",
        "FACE_TOO_SMALL" to "Vùng khuôn mặt quá nhỏ.",
        "FACE_CROPPED" to "Vùng khuôn mặt bị cắt.",
        "FACE_LANDMARKS_UNRELIABLE" to "Chưa xác định được các điểm trên khuôn mặt.",
        "FACE_ALIGNMENT_FAILED" to "Chưa căn chỉnh được khuôn mặt.",
        "FACE_BLURRY" to "Vùng khuôn mặt bị mờ.",
        "FACE_GLARE" to "Vùng khuôn mặt bị lóa.",
        "FACE_TOO_DARK" to "Vùng khuôn mặt quá tối.",
        "CARD_PREPARATION_FAILED" to "Chưa tìm được vùng ảnh chân dung trên thẻ.",
        "INVALID_IMAGE" to "Chưa đọc được ảnh để kiểm tra khuôn mặt.",
    )

    private val IMAGE_CODES = setOf(
        "IMAGE_BLURRY", "IMAGE_GLARE", "IMAGE_TOO_SMALL", "CARD_CROPPED", "CARD_NOT_FOUND",
    )

    private val FIELD_NAMES = mapOf(
        "CCCD" to mapOf(
            "id_number" to "cccd_id",
            "full_name" to "cccd_name",
            "dob" to "cccd_dob",
            "gender" to "cccd_gender",
            "expiry" to "cccd_expiry_date",
            "issue_date" to "cccd_issue_date",
            "address" to "cccd_address",
        ),
        "GPLX" to mapOf(
            "id_number" to "gplx_number",
            "full_name" to "gplx_name",
            "dob" to "gplx_dob",
            "vehicle_class" to "gplx_classes",
            "expiry" to "expiry_date",
        ),
    )

    fun normalizedName(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ").lowercase()

    fun nameComparison(left: String?, right: String?): String? {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return null
        val l = normalizedName(left)
        val r = normalizedName(right)
        if (l == r) return "EXACT_MATCH"
        fun fold(v: String): String =
            Normalizer.normalize(v, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace('đ', 'd')
        return if (fold(l) == fold(r)) "DIACRITIC_MISMATCH" else "NAME_MISMATCH"
    }

    fun comparisonWarnings(confirmed: Map<String, Any?>?): List<String> {
        if (confirmed == null) return emptyList()
        val cccdName = confirmed["cccd_name"] as? String
        val gplxName = confirmed["gplx_name"] as? String
        val name = nameComparison(cccdName, gplxName)
        val leftDob = (confirmed["cccd_dob"] as? String)?.trim()
        val rightDob = (confirmed["gplx_dob"] as? String)?.trim()
        val dobMismatch = !leftDob.isNullOrEmpty() && !rightDob.isNullOrEmpty() && leftDob != rightDob
        if (name == "NAME_MISMATCH" && dobMismatch) return listOf("NAME_AND_DOB_MISMATCH")
        val list = mutableListOf<String>()
        if (name == "DIACRITIC_MISMATCH" || name == "NAME_MISMATCH") list.add(name)
        if (dobMismatch) list.add("DOB_MISMATCH")
        return list
    }

    @Suppress("UNCHECKED_CAST")
    fun build(
        payload: Map<String, Any?>,
        revision: Long,
        faceStatuses: Collection<String> = emptyList(),
        faceQuality: Map<String, String>? = null,
        today: LocalDate = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")),
        identityContext: IdentityVerificationContext? = null,
    ): List<VerificationWarningDto> {
        data class Key(val code: String, val slot: String?, val doc: String?, val field: String?)
        val warnings = linkedMapOf<Key, VerificationWarningDto>()

        fun add(
            code: String,
            source: String,
            slot: String? = null,
            doc: String? = null,
            field: String? = null,
            severity: String = "WARNING",
            block: Boolean = false,
            message: String? = null,
        ) {
            val resolvedDoc = doc ?: slot?.substringBefore('_')?.uppercase()
            var action = when (source) {
                "CAPTURE" -> "Chụp lại ảnh theo khung hướng dẫn."
                "DETECTOR" -> "Chụp rõ toàn bộ thẻ và đủ bốn góc."
                "OCR" -> "Đối chiếu thông tin với giấy tờ gốc."
                "QR_OCR" -> "Đối chiếu hai đề xuất với giấy tờ gốc."
                "CONFIRMED_FIELDS" -> "Kiểm tra và sửa thông tin theo giấy tờ gốc."
                "FACE_COMPARISON" -> "Xem lại ảnh chân dung và selfie."
                "IDENTITY_LOCK" -> "Thông tin định danh đã xác minh không thể tự ý thay đổi."
                "CROSS_ACCOUNT" -> "Chuyển quản trị viên xử lý trùng giấy tờ giữa các tài khoản."
                else -> "Đối chiếu hồ sơ trước khi xét duyệt."
            }
            if (code.endsWith("_EXPIRED") && block) {
                action = "Bổ sung giấy tờ còn hiệu lực."
            }
            warnings[Key(code, slot, resolvedDoc, field)] = VerificationWarningDto(
                code = code,
                severity = severity,
                documentType = resolvedDoc,
                slot = slot,
                fieldKey = field,
                humanReadableMessage = message ?: TEXT[code] ?: code,
                recommendedAction = action,
                evidenceSource = source,
                documentRevision = revision,
                requiresReview = severity != "INFO",
                blocksSubmission = severity == "BLOCKING",
                blocksActivation = block,
            )
        }

        val captures = payload["captures"] as? Map<String, Any?> ?: emptyMap()
        val fields = payload["confirmed"] as? Map<String, Any?> ?: emptyMap()
        val legacyBackfill = payload["legacyBackfill"] == true

        if (!legacyBackfill) {
            for (slot in REQUIRED_SLOTS) {
                if (!captures.containsKey(slot)) {
                    add("DOCUMENT_REQUIRED", "CAPTURE", slot = slot, severity = "BLOCKING")
                }
            }
        }
        for ((slot, capObj) in captures) {
            val cap = capObj as? Map<String, Any?> ?: continue
            val quality = cap["quality"] as? Map<String, Any?> ?: continue
            val qWarnings = quality["warnings"] as? List<*> ?: continue
            for (raw in qWarnings) {
                val mapped = if (raw == "SMALL") "IMAGE_TOO_SMALL" else raw as? String
                if (mapped != null && mapped in IMAGE_CODES) {
                    add(mapped, "CAPTURE", slot = slot)
                }
            }
        }

        var cccdOcrExpiry: LocalDate? = null
        var cccdOcrNoExpiry = false
        var cccdOcrAttempted = false
        val cccdOcrFields = linkedMapOf<String, Any?>()
        val gplxOcrFields = linkedMapOf<String, Any?>()
        data class QrObservation(
            val doc: String,
            val slot: String,
            val fields: Map<String, Any?>,
            val source: String,
        )
        val qrObservations = mutableListOf<QrObservation>()

        val proposals = payload["proposals"] as? Map<String, Any?> ?: emptyMap()
        for ((slot, propObj) in proposals) {
            val prop = propObj as? Map<String, Any?> ?: continue
            val capture = captures[slot] as? Map<String, Any?>
            val propCapId = prop["captureId"] as? String
            val curCapId = capture?.get("id") as? String
            if (propCapId.isNullOrBlank() || propCapId != curCapId) continue
            val result = prop["result"] as? Map<String, Any?> ?: continue

            val rWarnings = result["warnings"] as? List<*> ?: emptyList<Any?>()
            for (raw in rWarnings) {
                val mapped = when (raw) {
                    "DETECTOR_NO_BOX", "DETECTOR_CARD_NOT_FOUND" -> "CARD_NOT_FOUND"
                    else -> raw as? String
                }
                if (mapped != null && mapped in IMAGE_CODES) {
                    add(mapped, "DETECTOR", slot = slot)
                }
            }

            val requiredFields = when (slot) {
                "cccd_front" -> setOf("id_number", "full_name", "dob")
                "gplx_front" -> setOf("id_number", "full_name", "dob", "vehicle_class", "expiry")
                else -> emptySet()
            }
            val candidates = (result["candidates"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
            val validKeys = candidates.filter {
                it["validationStatus"] == "valid" && it["conflict"] != true
            }.mapNotNull { it["fieldKey"] as? String }.toSet()

            val ocrStatus = result["ocrStatus"] as? String
            if (ocrStatus in setOf("OCR_FAILED", "OCR_NO_FIELDS", "OCR_NO_TEXT") ||
                (ocrStatus == "OCR_READ" && !validKeys.containsAll(requiredFields))
            ) {
                add("OCR_INCOMPLETE", "OCR", slot = slot)
            }

            val doc = slot.substringBefore('_').uppercase()
            if (slot in setOf("cccd_front", "cccd_back", "gplx_front", "gplx_back")) {
                val ocrBucket = if (doc == "GPLX") gplxOcrFields else cccdOcrFields
                for (cand in candidates) {
                    if (cand["source"] == "OCR" && cand["parseStatus"] == "parsed" &&
                        cand["validationStatus"] == "valid" && cand["candidateValue"] != null
                    ) {
                        val key = cand["fieldKey"] as? String
                        if (!key.isNullOrBlank()) {
                            ocrBucket.putIfAbsent(key, cand["candidateValue"])
                        }
                    }
                }
                val qrObj = result["qr"] as? Map<String, Any?>
                val qrStatus = qrObj?.get("status") as? String
                if (qrObj != null && qrStatus in setOf("DECODED_VALID", "CONFLICT")) {
                    val qrFields = qrObj["fields"] as? Map<String, Any?>
                    if (!qrFields.isNullOrEmpty()) {
                        qrObservations += QrObservation(doc, slot, qrFields, (qrObj["source"] as? String) ?: "QR_IMAGE")
                    }
                } else {
                    val slotQrFields = linkedMapOf<String, Any?>()
                    for (cand in candidates) {
                        if (cand["source"] == "QR" && cand["parseStatus"] == "parsed" &&
                            cand["validationStatus"] == "valid" && cand["candidateValue"] != null
                        ) {
                            val key = cand["fieldKey"] as? String
                            if (!key.isNullOrBlank()) {
                                slotQrFields[key] = cand["candidateValue"]
                            }
                        }
                    }
                    if (slotQrFields.isNotEmpty()) {
                        qrObservations += QrObservation(doc, slot, slotQrFields, "QR_IMAGE")
                    }
                }
            }

            val conflictFields = result["conflictFields"] as? List<*> ?: emptyList<Any?>()
            for (rawKey in conflictFields) {
                val key = rawKey as? String ?: continue
                val sources = candidates.filter {
                    it["fieldKey"] == key && it["conflict"] == true && it["source"] is String
                }.map { it["source"] as String }.toSet()
                val mappedField = FIELD_NAMES[doc]?.get(key)
                if (sources.containsAll(setOf("QR", "OCR"))) {
                    if (key == "full_name" && slot in setOf("cccd_front", "cccd_back", "gplx_front", "gplx_back")) {
                        val qrName = candidates.firstOrNull {
                            it["fieldKey"] == "full_name" && it["source"] == "QR" && it["candidateValue"] != null
                        }?.get("candidateValue") as? String
                        val ocrName = candidates.firstOrNull {
                            it["fieldKey"] == "full_name" && it["source"] == "OCR" && it["candidateValue"] != null
                        }?.get("candidateValue") as? String
                        if (nameComparison(qrName, ocrName) == "DIACRITIC_MISMATCH") {
                            continue
                        }
                    }
                    add("QR_OCR_CONFLICT", "QR_OCR", slot = slot, field = mappedField)
                } else if ("OCR" in sources) {
                    add("OCR_INCOMPLETE", "OCR", slot = slot, field = mappedField)
                }
            }

            val statuses = result["fieldStatuses"] as? Map<String, Any?> ?: emptyMap()
            if (slot == "gplx_front") {
                val expStatus = statuses["gplx_expiry"] as? String
                if (expStatus in setOf("OCR_MISSING", "PARSE_FAILED", "VALIDATION_FAILED") &&
                    !(fields["no_expiry"] == true || parseDate(fields["expiry_date"]) != null)
                ) {
                    add("GPLX_EXPIRY_UNKNOWN", "OCR", slot = slot, field = "expiry_date")
                }
                if (statuses["gplx_restriction"] in setOf("UNKNOWN", "UNREADABLE")) {
                    add("GPLX_RESTRICTION_UNKNOWN", "OCR", slot = slot, severity = "CRITICAL", block = true)
                }
            }

            if (slot == "cccd_front") {
                cccdOcrAttempted = ocrStatus in setOf("OCR_READ", "OCR_NO_FIELDS", "OCR_NO_TEXT", "OCR_FAILED")
                if ("ORIENTATION_UNCERTAIN" !in rWarnings) {
                    val expiryCandidate = candidates.firstOrNull {
                        it["fieldKey"] == "expiry" &&
                            it["source"] == "OCR" &&
                            it["parseStatus"] == "parsed" &&
                            it["validationStatus"] == "valid" &&
                            it["conflict"] != true
                    }?.get("candidateValue") as? Map<String, Any?>
                    if (expiryCandidate != null) {
                        if (expiryCandidate["no_expiry"] == false) {
                            cccdOcrExpiry = parseDate(expiryCandidate["expiry_date"])
                        } else if (expiryCandidate["no_expiry"] == true &&
                            expiryCandidate.containsKey("expiry_date") &&
                            expiryCandidate["expiry_date"] == null
                        ) {
                            cccdOcrNoExpiry = true
                        }
                    }
                }
            }
        }

        for ((liveDoc, liveKey) in listOf("CCCD" to "qrLiveScan", "GPLX" to "gplxQrLiveScan")) {
            val liveQr = payload[liveKey] as? Map<String, Any?>
            if (liveQr != null && (liveQr["status"] as? String) in setOf("DECODED_VALID", "CONFLICT")) {
                val liveSlot = if (liveDoc == "GPLX") {
                    if (liveQr["documentSide"] == "FRONT") "gplx_front" else "gplx_back"
                } else {
                    if (liveQr["documentSide"] == "BACK") "cccd_back" else "cccd_front"
                }
                val liveFields = liveQr["fields"] as? Map<String, Any?>
                if (!liveFields.isNullOrEmpty()) {
                    qrObservations += QrObservation(liveDoc, liveSlot, liveFields, "QR_LIVE_SCAN")
                }
                if (liveQr["boundToCapture"] != true) {
                    add("QR_LIVE_SCAN_UNBOUND", "QR_OCR", slot = liveSlot, doc = liveDoc, severity = "INFO")
                }
            }
        }

        for ((qrDoc, qrSlot, qrFields, _) in qrObservations) {
            val ocrPool = if (qrDoc == "GPLX") gplxOcrFields else cccdOcrFields
            val summary = CccdQrParser.compareQrAndOcr(qrFields, ocrPool)
            for (key in summary.conflictFields) {
                val mappedField = FIELD_NAMES[qrDoc]?.get(key) ?: key
                if (key in summary.diacriticMismatchFields) {
                    add("QR_OCR_NAME_DIACRITIC_MISMATCH", "QR_OCR", slot = qrSlot, doc = qrDoc, field = mappedField, severity = "WARNING")
                } else {
                    val sev = if (key in setOf("id_number", "dob", "full_name")) "CRITICAL" else "WARNING"
                    add("QR_OCR_CONFLICT", "QR_OCR", slot = qrSlot, doc = qrDoc, field = mappedField, severity = sev)
                }
            }
            if (summary.status == "INSUFFICIENT_EVIDENCE") {
                add("QR_OCR_INSUFFICIENT_EVIDENCE", "QR_OCR", slot = qrSlot, doc = qrDoc, severity = "INFO")
            }
            val qrId = (qrFields["id_number"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (qrDoc == "CCCD" && identityContext?.lockedCccdId != null && qrId != null && qrId != identityContext.lockedCccdId) {
                add("LOCKED_CCCD_ID_CHANGED", "IDENTITY_LOCK", slot = qrSlot, field = "cccd_id", severity = "BLOCKING", block = true)
            }
        }

        val nameCmp = nameComparison(fields["cccd_name"] as? String, fields["gplx_name"] as? String)
        if (nameCmp == "DIACRITIC_MISMATCH") {
            add(nameCmp, "CONFIRMED_FIELDS", doc = "GPLX", field = "gplx_name", severity = "WARNING", block = false)
        } else if (nameCmp == "NAME_MISMATCH") {
            add(nameCmp, "CONFIRMED_FIELDS", doc = "GPLX", field = "gplx_name", severity = "CRITICAL", block = true)
        }
        val leftDob = fields["cccd_dob"] as? String
        val rightDob = fields["gplx_dob"] as? String
        if (!leftDob.isNullOrBlank() && !rightDob.isNullOrBlank() && leftDob != rightDob) {
            add("DOB_MISMATCH", "CONFIRMED_FIELDS", doc = "GPLX", field = "gplx_dob", severity = "CRITICAL", block = true)
        }

        if (identityContext != null) {
            val cccdId = (fields["cccd_id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (identityContext.lockedCccdId != null && cccdId != null && cccdId != identityContext.lockedCccdId) {
                add("LOCKED_CCCD_ID_CHANGED", "IDENTITY_LOCK", slot = "cccd_front", field = "cccd_id", severity = "BLOCKING", block = true)
            }
            val cccdName = (fields["cccd_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (identityContext.lockedFullName != null && cccdName != null &&
                nameComparison(cccdName, identityContext.lockedFullName) == "NAME_MISMATCH"
            ) {
                add("LOCKED_IDENTITY_NAME_MISMATCH", "IDENTITY_LOCK", slot = "cccd_front", field = "cccd_name", severity = "CRITICAL", block = true)
            }
            val cccdDob = parseDate(fields["cccd_dob"])
            if (identityContext.lockedDob != null && cccdDob != null && cccdDob != identityContext.lockedDob) {
                add("LOCKED_IDENTITY_DOB_MISMATCH", "IDENTITY_LOCK", slot = "cccd_front", field = "cccd_dob", severity = "CRITICAL", block = true)
            }
            val gplxName = (fields["gplx_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val gplxDob = parseDate(fields["gplx_dob"])
            if (identityContext.lockedFullName != null && gplxName != null &&
                nameComparison(gplxName, identityContext.lockedFullName) == "NAME_MISMATCH"
            ) {
                add("GPLX_HOLDER_MISMATCH", "IDENTITY_LOCK", slot = "gplx_front", field = "gplx_name", severity = "CRITICAL", block = true)
            }
            if (identityContext.lockedDob != null && gplxDob != null && gplxDob != identityContext.lockedDob) {
                add("GPLX_HOLDER_MISMATCH", "IDENTITY_LOCK", slot = "gplx_front", field = "gplx_dob", severity = "CRITICAL", block = true)
            }
            if (identityContext.duplicateCccdAccount) {
                add("DUPLICATE_CCCD_ACROSS_ACCOUNTS", "CROSS_ACCOUNT", slot = "cccd_front", field = "cccd_id", severity = "CRITICAL", block = true)
            }
            if (identityContext.duplicateGplxAccount) {
                add("DUPLICATE_GPLX_ACROSS_ACCOUNTS", "CROSS_ACCOUNT", slot = "gplx_front", field = "gplx_number", severity = "CRITICAL", block = true)
            }
            val prev = identityContext.previousApprovedConfirmed
            if (prev != null && fields.isNotEmpty()) {
                val prevCccdExp = (prev["cccd_expiry_date"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val curCccdExp = (fields["cccd_expiry_date"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                if (prevCccdExp != curCccdExp && curCccdExp != null) {
                    add("CCCD_RENEWAL_REQUIRES_REVIEW", "CONFIRMED_FIELDS", slot = "cccd_front", field = "cccd_expiry_date", severity = "INFO", block = false)
                }
                val prevGplxNum = (prev["gplx_number"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val curGplxNum = (fields["gplx_number"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val prevClasses = (prev["gplx_classes"] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.uppercase() }?.sorted().orEmpty()
                val curClasses = (fields["gplx_classes"] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.uppercase() }?.sorted().orEmpty()
                val prevNoExp = prev["no_expiry"] == true
                val curNoExp = fields["no_expiry"] == true
                val prevExp = (prev["expiry_date"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val curExp = (fields["expiry_date"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                if ((curGplxNum != null && prevGplxNum != curGplxNum) ||
                    (curClasses.isNotEmpty() && prevClasses != curClasses) ||
                    prevNoExp != curNoExp ||
                    prevExp != curExp
                ) {
                    add("GPLX_UPDATED_REQUIRES_REVIEW", "CONFIRMED_FIELDS", slot = "gplx_front", field = "gplx_classes", severity = "INFO", block = false)
                }
            }
        }

        val cccdDate = parseDate(fields["cccd_expiry_date"])
        if (cccdDate != null && cccdDate.isBefore(today)) {
            add("CCCD_EXPIRED", "CONFIRMED_FIELDS", slot = "cccd_front", field = "cccd_expiry_date", severity = "CRITICAL", block = true)
        } else if (cccdDate == null && cccdOcrAttempted) {
            if (cccdOcrExpiry != null && cccdOcrExpiry.isBefore(today)) {
                add("CCCD_EXPIRED", "OCR", slot = "cccd_front", field = "cccd_expiry_date", severity = "CRITICAL", block = true)
            } else if (cccdOcrExpiry == null && !cccdOcrNoExpiry) {
                add("CCCD_EXPIRY_UNKNOWN", "OCR", slot = "cccd_front", field = "cccd_expiry_date", severity = "INFO")
            }
        }

        if (fields.isNotEmpty()) {
            val gplxExpiry = parseDate(fields["expiry_date"])
            if (gplxExpiry != null && gplxExpiry.isBefore(today)) {
                add("GPLX_EXPIRED", "CONFIRMED_FIELDS", slot = "gplx_front", field = "expiry_date", severity = "CRITICAL", block = true)
            } else if (gplxExpiry == null && fields["no_expiry"] != true) {
                add("GPLX_EXPIRY_UNKNOWN", "CONFIRMED_FIELDS", slot = "gplx_front", field = "expiry_date", severity = "CRITICAL", block = true)
            }
            val classes = (fields["gplx_classes"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            if (classes.isEmpty() || classes.any { it !in LICENSE_CLASSES }) {
                add("GPLX_CLASS_UNKNOWN", "CONFIRMED_FIELDS", slot = "gplx_front", field = "gplx_classes", severity = "CRITICAL", block = true)
            }
        }

        if ("POSSIBLE_MISMATCH" in faceStatuses) {
            add("FACE_POSSIBLE_MISMATCH", "FACE_COMPARISON", slot = "selfie", severity = "CRITICAL")
        }
        val flaggedSlots = faceQuality?.entries
            ?.filter { (slot, code) -> slot in FACE_SLOTS && code in FACE_QUALITY_MESSAGES }
            ?.sortedBy { it.key }
            .orEmpty()
        for ((slot, code) in flaggedSlots) {
            add("FACE_INSUFFICIENT_QUALITY", "FACE_COMPARISON", slot = slot, message = FACE_QUALITY_MESSAGES[code])
        }
        if ("INSUFFICIENT_QUALITY" in faceStatuses && flaggedSlots.isEmpty()) {
            add("FACE_INSUFFICIENT_QUALITY", "FACE_COMPARISON")
        }
        if (faceStatuses.isEmpty() || faceStatuses.toSet() == setOf("NOT_EVALUATED")) {
            add("FACE_NOT_EVALUATED", "FACE_COMPARISON", slot = "selfie", severity = "INFO")
        }
        return warnings.values.toList()
    }

    fun parseDate(value: Any?): LocalDate? {
        val str = value as? String ?: return null
        return runCatching { LocalDate.parse(str.trim()) }.getOrNull()
    }
}
