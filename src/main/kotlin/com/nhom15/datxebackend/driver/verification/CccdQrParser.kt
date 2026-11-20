package com.nhom15.datxebackend.driver.verification

import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class CccdQrStatus {
    NOT_FOUND,
    DETECTED_UNREADABLE,
    DECODED_VALID,
    INVALID_FORMAT,
    UNSUPPORTED_FORMAT,
    CONFLICT,
    ENGINE_UNAVAILABLE,
}

data class CccdQrParseResult(
    val status: CccdQrStatus,
    val fields: Map<String, Any?> = emptyMap(),
    val missingFields: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class CccdQrCrossValidationSummary(
    val status: String,
    val conflictFields: List<String>,
    val diacriticMismatchFields: List<String>,
    val matchedFields: List<String>,
)

object CccdQrParser {
    private const val MAX_PAYLOAD_LENGTH = 1024
    private val ID12_REGEX = Regex("^[0-9]{12}$")
    private val OLD_ID_REGEX = Regex("^(?:[0-9]{8,12})?$")
    private val GPLX_ID_REGEX = Regex("^(?=.*\\d)[A-Z0-9]{6,20}$")
    private val GPLX_CLASS_REGEX = Regex("^[A-Z0-9]{1,8}$")
    private val DATE_FORMATTERS = listOf(
        DateTimeFormatter.ofPattern("ddMMuuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT),
    )

    fun normalizeText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ")

    fun parseQrDate(value: String?): LocalDate? {
        val cleaned = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        for (formatter in DATE_FORMATTERS) {
            val parsed = runCatching { LocalDate.parse(cleaned, formatter) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    fun normalizeIsoDate(value: Any?): String? {
        val str = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val iso = runCatching { LocalDate.parse(str) }.getOrNull()
        if (iso != null) return iso.toString()
        return parseQrDate(str)?.toString()
    }

    private fun validPersonName(name: String): Boolean {
        if (name.isEmpty() || name.length > 150) return false
        if (!name.any { it.isLetter() }) return false
        return name.all { it.isLetter() || it in setOf(' ', '-', '\'', '.') }
    }

    private fun parseGender(raw: String): String? {
        val normalized = normalizeText(raw).lowercase()
        if (normalized.isEmpty()) return null
        return when (normalized) {
            "nam" -> "MALE"
            "nữ", "nu" -> "FEMALE"
            else -> "__INVALID__"
        }
    }

    /**
     * Parses Vietnamese CCCD 2021 and Can Cuoc 2024 pipe-delimited QR payloads
     * without hardcoding 7 fields or requiring a 14-year gap between DOB and issue date.
     */
    fun parse(
        payload: String?,
        slot: String = "cccd_front",
        today: LocalDate = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")),
    ): CccdQrParseResult {
        if (payload.isNullOrBlank()) {
            return CccdQrParseResult(status = CccdQrStatus.NOT_FOUND)
        }
        val lowerSlot = slot.lowercase()
        if (lowerSlot.startsWith("gplx")) {
            return parseGplx(payload, today)
        }
        if (!lowerSlot.startsWith("cccd")) {
            return CccdQrParseResult(status = CccdQrStatus.UNSUPPORTED_FORMAT)
        }
        val raw = payload.trim()
        if (raw.length > MAX_PAYLOAD_LENGTH) {
            val firstPart = raw.substringBefore('|').trim()
            val looksCccd = raw.contains('|') && ID12_REGEX.matches(firstPart)
            return CccdQrParseResult(
                status = if (looksCccd) CccdQrStatus.INVALID_FORMAT else CccdQrStatus.UNSUPPORTED_FORMAT,
            )
        }

        val parts = raw.split('|').map(::normalizeText)
        if (parts.size < 5) {
            val isTruncatedCccd = parts.size >= 2 && ID12_REGEX.matches(parts[0])
            return CccdQrParseResult(
                status = if (isTruncatedCccd) CccdQrStatus.INVALID_FORMAT else CccdQrStatus.UNSUPPORTED_FORMAT,
            )
        }
        if (parts.size > 12) {
            return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
        }

        val idNumber = parts[0]
        if (!ID12_REGEX.matches(idNumber)) {
            return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
        }

        val rawName: String
        val rawDob: String
        val rawGender: String
        val rawAddress: String
        val rawIssue: String

        when {
            parts.size >= 7 && (parts[1].isEmpty() || OLD_ID_REGEX.matches(parts[1])) -> {
                rawName = parts[2]
                rawDob = parts[3]
                rawGender = parts[4]
                rawAddress = parts[5]
                rawIssue = parts[6]
            }
            parts.size == 6 && (parts[1].isEmpty() || OLD_ID_REGEX.matches(parts[1])) &&
                (parts[3].isEmpty() || parseQrDate(parts[3]) != null) -> {
                rawName = parts[2]
                rawDob = parts[3]
                rawGender = parts[4]
                rawAddress = parts[5]
                rawIssue = ""
            }
            !parts[1].all { it.isDigit() } && (parts[2].isEmpty() || parseQrDate(parts[2]) != null) -> {
                rawName = parts[1]
                rawDob = parts[2]
                rawGender = parts[3]
                rawAddress = parts[4]
                rawIssue = if (parts.size > 5) parts[5] else ""
            }
            else -> return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
        }

        val fields = linkedMapOf<String, Any?>("id_number" to idNumber)
        val missing = mutableListOf<String>()

        if (rawName.isNotEmpty()) {
            if (!validPersonName(rawName)) {
                return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            }
            fields["full_name"] = rawName
        } else {
            missing.add("full_name")
        }

        var parsedDob: LocalDate? = null
        if (rawDob.isNotEmpty()) {
            parsedDob = parseQrDate(rawDob)
            if (parsedDob == null || parsedDob.year < 1900 || parsedDob.isAfter(today)) {
                return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            }
            fields["dob"] = parsedDob.toString()
        } else {
            missing.add("dob")
        }

        if (!fields.containsKey("full_name") && !fields.containsKey("dob")) {
            return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
        }

        if (rawGender.isNotEmpty()) {
            val gender = parseGender(rawGender)
            if (gender == "__INVALID__" || gender == null) {
                return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            }
            fields["gender"] = gender
        } else {
            missing.add("gender")
        }

        if (rawAddress.isNotEmpty()) {
            if (rawAddress.length > 300) {
                return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            }
            fields["address"] = rawAddress
        } else {
            missing.add("address")
        }

        if (rawIssue.isNotEmpty()) {
            val parsedIssue = parseQrDate(rawIssue)
            if (parsedIssue == null || parsedIssue.isAfter(today) ||
                (parsedDob != null && parsedIssue.isBefore(parsedDob))
            ) {
                return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            }
            fields["issue_date"] = parsedIssue.toString()
        } else {
            missing.add("issue_date")
        }

        val warnings = if (missing.isNotEmpty()) listOf("QR_PARTIAL_FIELDS") else emptyList()
        return CccdQrParseResult(
            status = CccdQrStatus.DECODED_VALID,
            fields = fields,
            missingFields = missing,
            warnings = warnings,
        )
    }

    private fun foldDiacritics(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('đ', 'd')
            .replace('Đ', 'D')

    private fun normalizeGplxClasses(raw: String): List<String>? {
        val tokens = normalizeText(raw)
            .split(Regex("[,;/\\s]+"))
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (tokens.isEmpty() || tokens.size > 20 || tokens.any { !GPLX_CLASS_REGEX.matches(it) }) {
            return null
        }
        return tokens
    }

    private fun classSet(value: Any?): Set<String>? {
        val tokens = when (value) {
            is Collection<*> -> value.mapNotNull { (it as? String)?.trim()?.uppercase()?.takeIf { s -> s.isNotEmpty() } }
            is String -> value.split(Regex("[,;/\\s]+")).map { it.trim().uppercase() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        return tokens.takeIf { it.isNotEmpty() }?.toSet()
    }

    fun parseGplx(
        payload: String?,
        today: LocalDate = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")),
    ): CccdQrParseResult {
        if (payload.isNullOrBlank()) {
            return CccdQrParseResult(status = CccdQrStatus.NOT_FOUND)
        }
        val raw = payload.trim()
        val lower = raw.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return CccdQrParseResult(status = CccdQrStatus.UNSUPPORTED_FORMAT)
        }
        if (raw.length > MAX_PAYLOAD_LENGTH) {
            return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
        }

        var rawId = ""
        var rawName = ""
        var rawDob = ""
        var rawClasses = ""
        var rawExpiry = ""
        var rawIssueDate = ""
        var noExpiry = false

        when {
            ';' in raw.lineSequence().first() && '|' !in raw.lineSequence().first() -> {
                val lines = raw.lines().map(String::trim).filter(String::isNotEmpty)
                val parts = lines.first().split(';').map(::normalizeText)
                if (parts.size != 7 || parts.any(String::isBlank) ||
                    lines.drop(1).any { !it.startsWith("https://", ignoreCase = true) && !it.startsWith("http://", ignoreCase = true) }
                ) {
                    return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
                }
                rawId = parts[0].uppercase()
                rawName = parts[1]
                rawDob = parts[2]
                rawClasses = parts[3]
                rawIssueDate = parts[4]
                noExpiry = foldDiacritics(parts[5]).lowercase() == "khong thoi han"
                if (!noExpiry) rawExpiry = parts[5]
            }
            '|' in raw -> {
                val parts = raw.split('|').map(::normalizeText)
                if (parts.size < 4) {
                    return CccdQrParseResult(status = CccdQrStatus.UNSUPPORTED_FORMAT)
                }
                if (parts.size > 10) {
                    return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
                }
                if (parts[0].isNotEmpty() && GPLX_ID_REGEX.matches(parts[0].uppercase())) {
                    rawId = parts[0].uppercase()
                    rawName = parts[1]
                    rawDob = parts[2]
                    rawClasses = parts[3]
                    if (parts.size >= 6 && parseQrDate(parts[5]) != null) {
                        rawExpiry = parts[5]
                    }
                } else {
                    rawName = parts[0]
                    rawDob = parts[1]
                    rawClasses = parts[2]
                    if (parts.size >= 5 && parseQrDate(parts[4]) != null) {
                        rawExpiry = parts[4]
                    }
                }
            }
            '\n' in raw || '\r' in raw -> {
                val lines = raw.lines().map(::normalizeText).filter { it.isNotEmpty() }
                if (lines.size < 3) {
                    return CccdQrParseResult(status = CccdQrStatus.UNSUPPORTED_FORMAT)
                }
                var labeled = false
                val unlabeled = mutableListOf<String>()
                for (line in lines) {
                    if (':' in line) {
                        val splitIdx = line.indexOf(':')
                        val label = foldDiacritics(normalizeText(line.substring(0, splitIdx))).lowercase()
                        val value = normalizeText(line.substring(splitIdx + 1))
                        when {
                            "so gplx" in label || "so giay phep" in label || "ma so" in label -> {
                                labeled = true
                                rawId = value.uppercase()
                            }
                            "ho ten" in label || "ho va ten" in label || label == "ten" -> {
                                labeled = true
                                rawName = value
                            }
                            "ngay sinh" in label || "sinh" in label -> {
                                labeled = true
                                rawDob = value
                            }
                            "hang" in label -> {
                                labeled = true
                                rawClasses = value
                            }
                            "het han" in label || "gia tri den" in label || "co gia tri" in label -> {
                                labeled = true
                                rawExpiry = value
                            }
                            "noi cap" in label || "co quan cap" in label || "don vi cap" in label -> {
                                labeled = true
                            }
                            else -> unlabeled.add(line)
                        }
                    } else {
                        unlabeled.add(line)
                    }
                }
                if (!labeled) {
                    if (unlabeled.size >= 4 && GPLX_ID_REGEX.matches(unlabeled[0].uppercase())) {
                        rawId = unlabeled[0].uppercase()
                        rawName = unlabeled[1]
                        rawDob = unlabeled[2]
                        rawClasses = unlabeled[3]
                    } else if (unlabeled.size >= 3) {
                        rawName = unlabeled[0]
                        rawDob = unlabeled[1]
                        rawClasses = unlabeled[2]
                    }
                }
            }
            else -> return CccdQrParseResult(status = CccdQrStatus.UNSUPPORTED_FORMAT)
        }

        val fields = linkedMapOf<String, Any?>()
        val missing = mutableListOf<String>()

        if (rawId.isNotEmpty()) {
            if (!GPLX_ID_REGEX.matches(rawId)) {
                return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            }
            fields["id_number"] = rawId
        } else {
            missing.add("id_number")
        }

        if (rawName.isNotEmpty()) {
            if (!validPersonName(rawName)) {
                return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            }
            fields["full_name"] = rawName
        } else {
            missing.add("full_name")
        }

        if (rawDob.isNotEmpty()) {
            val parsedDob = parseQrDate(rawDob)
            if (parsedDob == null || parsedDob.year < 1900 || parsedDob.isAfter(today)) {
                return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            }
            fields["dob"] = parsedDob.toString()
        } else {
            missing.add("dob")
        }

        if (rawClasses.isNotEmpty()) {
            val classes = normalizeGplxClasses(rawClasses)
                ?: return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            fields["vehicle_class"] = classes
        } else {
            missing.add("vehicle_class")
        }

        if (rawIssueDate.isNotEmpty()) {
            val issued = parseQrDate(rawIssueDate)
                ?: return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            val birth = (fields["dob"] as? String)?.let(LocalDate::parse)
            if (issued.isAfter(today) || (birth != null && issued.isBefore(birth))) {
                return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            }
            fields["issue_date"] = issued.toString()
        }
        if (noExpiry) {
            fields["expiry"] = mapOf("no_expiry" to true, "expiry_date" to null)
        } else if (rawExpiry.isNotEmpty()) {
            val parsedExpiry = parseQrDate(rawExpiry)
                ?: return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            val issued = (fields["issue_date"] as? String)?.let(LocalDate::parse)
            if (issued != null && parsedExpiry.isBefore(issued)) {
                return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
            }
            fields["expiry"] = parsedExpiry.toString()
        }

        if (!fields.containsKey("full_name") ||
            (!fields.containsKey("dob") && !fields.containsKey("vehicle_class") && !fields.containsKey("id_number"))
        ) {
            return CccdQrParseResult(status = CccdQrStatus.INVALID_FORMAT)
        }

        val warnings = if (missing.isNotEmpty()) listOf("QR_PARTIAL_FIELDS") else emptyList()
        return CccdQrParseResult(
            status = CccdQrStatus.DECODED_VALID,
            fields = fields,
            missingFields = missing,
            warnings = warnings,
        )
    }

    fun compareQrAndOcr(
        qrFields: Map<String, Any?>,
        ocrFields: Map<String, Any?>,
    ): CccdQrCrossValidationSummary {
        val conflicts = mutableListOf<String>()
        val diacriticMismatches = mutableListOf<String>()
        val matched = mutableListOf<String>()

        val qId = (qrFields["id_number"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val oId = (ocrFields["id_number"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (qId != null && oId != null) {
            if (qId == oId) matched.add("id_number") else conflicts.add("id_number")
        }

        val qDob = normalizeIsoDate(qrFields["dob"])
        val oDob = normalizeIsoDate(ocrFields["dob"])
        if (qDob != null && oDob != null) {
            if (qDob == oDob) matched.add("dob") else conflicts.add("dob")
        }

        val qName = (qrFields["full_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val oName = (ocrFields["full_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        if (qName != null && oName != null) {
            when (DriverVerificationWarnings.nameComparison(qName, oName)) {
                "EXACT_MATCH" -> matched.add("full_name")
                "DIACRITIC_MISMATCH" -> {
                    conflicts.add("full_name")
                    diacriticMismatches.add("full_name")
                }
                "NAME_MISMATCH" -> conflicts.add("full_name")
            }
        }

        val qClasses = classSet(qrFields["vehicle_class"])
        val oClasses = classSet(ocrFields["vehicle_class"])
        if (qClasses != null && oClasses != null) {
            if (qClasses == oClasses) matched.add("vehicle_class") else conflicts.add("vehicle_class")
        }

        val qGender = (qrFields["gender"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val oGender = (ocrFields["gender"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (qGender != null && oGender != null) {
            if (qGender == oGender) matched.add("gender") else conflicts.add("gender")
        }

        val qIssue = normalizeIsoDate(qrFields["issue_date"])
        val oIssue = normalizeIsoDate(ocrFields["issue_date"])
        if (qIssue != null && oIssue != null) {
            if (qIssue == oIssue) matched.add("issue_date") else conflicts.add("issue_date")
        }

        val status = when {
            conflicts.isNotEmpty() -> "CONFLICT"
            matched.any { it in setOf("id_number", "full_name", "dob", "vehicle_class") } -> "MATCHED"
            else -> "INSUFFICIENT_EVIDENCE"
        }
        return CccdQrCrossValidationSummary(
            status = status,
            conflictFields = conflicts,
            diacriticMismatchFields = diacriticMismatches,
            matchedFields = matched,
        )
    }

    fun computeLookupHmac(value: String, secretKey: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey, "HmacSHA256"))
        return mac.doFinal(value.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
