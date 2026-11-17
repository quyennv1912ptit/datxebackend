package com.nhom15.datxebackend.driver.verification

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.min

data class CheckedCapture(
    val width: Int,
    val height: Int,
    val warnings: List<String>,
    val sha256: String,
    val size: Int,
)

class VerificationWorkflowException(
    val httpStatus: Int,
    val errorCode: String,
    override val message: String,
) : RuntimeException(message)

@Service
class DriverVerificationStorageService(
    @Value("\${app.verification.storage-dir:./data/driver-verification-captures}") storageDir: String,
    @Value("\${app.verification.encryption-key:}") encryptionKey: String,
    @Value("\${app.verification.require-protected-storage:false}") private val protectedStorage: Boolean = false,
) {
    private val root: Path = Paths.get(storageDir).toAbsolutePath().normalize()
    private val aesKey: SecretKeySpec? = encryptionKey.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }
    private val rng = SecureRandom()

    init {
        if (protectedStorage) {
            require(aesKey != null) { "Production verification storage requires an encryption key." }
            require(storageDir.isNotBlank() && Paths.get(storageDir).isAbsolute && Files.isDirectory(root) && Files.isWritable(root)) {
                "Production verification storage requires a provisioned writable absolute directory."
            }
            val sourceRoot = Paths.get("").toAbsolutePath().toRealPath()
            val resolvedRoot = root.toRealPath()
            require(resolvedRoot.parent != null && !resolvedRoot.startsWith(sourceRoot)) {
                "Production verification storage must be outside the application directory."
            }
        } else {
            Files.createDirectories(root)
        }
    }

    fun inspectImage(bytes: ByteArray, mimeType: String): CheckedCapture {
        if (bytes.isEmpty()) {
            throw VerificationWorkflowException(400, "INVALID_IMAGE", "Ảnh rỗng hoặc không hợp lệ.")
        }
        if (bytes.size > 8_000_000) {
            throw VerificationWorkflowException(413, "IMAGE_TOO_LARGE", "Ảnh vượt quá giới hạn 8 MB.")
        }
        if (!matchesMagic(bytes, mimeType)) {
            throw VerificationWorkflowException(400, "INVALID_IMAGE", "Định dạng ảnh không khớp nội dung tệp.")
        }
        val decodedDimensions = try {
            readImageDimensions(bytes)
        } catch (_: Exception) {
            null
        }
        val webpDimensions = if (decodedDimensions == null && mimeType.equals("image/webp", ignoreCase = true)) {
            readWebpDimensions(bytes)
        } else null
        val dimensions = decodedDimensions ?: webpDimensions
            ?: throw VerificationWorkflowException(400, "INVALID_IMAGE", "Không đọc được kích thước ảnh.")
        if (!dimensions.withinLimits()) {
            throw VerificationWorkflowException(400, "INVALID_IMAGE", "Kích thước ảnh nằm ngoài giới hạn cho phép.")
        }
        val (width, height) = dimensions
        val warnings = mutableListOf<String>()
        if (min(width, height) < 480) warnings.add("SMALL")
        return CheckedCapture(
            width = width,
            height = height,
            warnings = warnings,
            sha256 = sha256Hex(bytes),
            size = bytes.size,
        )
    }

    fun store(bytes: ByteArray): String {
        val objectKey = UUID.randomUUID().toString().replace("-", "")
        val target = resolveSafe(objectKey)
        val payload = aesKey?.let { encrypt(it, bytes) } ?: bytes
        Files.write(target, payload)
        return objectKey
    }

    fun read(objectKey: String, expectedSize: Int, expectedSha256: String): ByteArray {
        val target = resolveSafe(objectKey)
        if (!Files.isRegularFile(target)) {
            throw VerificationWorkflowException(503, "VERIFICATION_UNAVAILABLE", "Không tìm thấy tệp ảnh đã lưu.")
        }
        val stored = try {
            Files.readAllBytes(target)
        } catch (_: Exception) {
            throw VerificationWorkflowException(503, "VERIFICATION_UNAVAILABLE", "Không đọc được tệp ảnh.")
        }
        val encrypted = isEncryptedEnvelope(stored)
        if (protectedStorage && !encrypted) {
            throw VerificationWorkflowException(503, "VERIFICATION_UNAVAILABLE", "Ảnh cần được chuyển sang kho lưu trữ bảo mật.")
        }
        val plain = if (aesKey != null && encrypted) decrypt(aesKey, stored) else stored
        if (plain.size != expectedSize || sha256Hex(plain) != expectedSha256) {
            throw VerificationWorkflowException(503, "VERIFICATION_UNAVAILABLE", "Kiểm tra toàn vẹn ảnh thất bại.")
        }
        return plain
    }

    fun deleteQuietly(objectKey: String) {
        runCatching { Files.deleteIfExists(resolveSafe(objectKey)) }
    }

    private fun resolveSafe(objectKey: String): Path {
        if (!objectKey.matches(Regex("^[A-Za-z0-9_-]{8,128}$"))) {
            throw VerificationWorkflowException(400, "INVALID_REQUEST", "Khóa lưu trữ không hợp lệ.")
        }
        val resolved = root.resolve(objectKey).normalize()
        require(resolved.startsWith(root)) { "Storage path escapes root" }
        return resolved
    }

    private fun encrypt(key: SecretKeySpec, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(rng::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(plain)
        return ENCRYPTED_MAGIC + iv + sealed
    }

    private fun decrypt(key: SecretKeySpec, payload: ByteArray): ByteArray {
        if (payload.size <= ENCRYPTED_MAGIC.size + 12) {
            throw VerificationWorkflowException(503, "VERIFICATION_UNAVAILABLE", "Dữ liệu mã hóa không hợp lệ.")
        }
        val ivStart = ENCRYPTED_MAGIC.size
        val iv = payload.copyOfRange(ivStart, ivStart + 12)
        val ciphertext = payload.copyOfRange(ivStart + 12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun isEncryptedEnvelope(bytes: ByteArray): Boolean =
        bytes.size > ENCRYPTED_MAGIC.size && bytes.copyOfRange(0, ENCRYPTED_MAGIC.size).contentEquals(ENCRYPTED_MAGIC)

    private fun matchesMagic(bytes: ByteArray, mimeType: String): Boolean = when (mimeType.lowercase()) {
        "image/jpeg" -> bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        "image/png" -> bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        "image/webp" -> bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        else -> false
    }

    private data class Dim(val width: Int, val height: Int) {
        fun withinLimits(): Boolean = width >= 2 && height >= 2 && width.toLong() * height <= 40_000_000L
    }

    private fun readImageDimensions(bytes: ByteArray): Dim? {
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                val dimensions = Dim(reader.getWidth(0), reader.getHeight(0))
                if (!dimensions.withinLimits()) return dimensions
                // Decode in-bounds images too, so malformed image data is still rejected.
                val image = reader.read(0) ?: return null
                return Dim(image.width, image.height)
            } finally {
                reader.dispose()
            }
        }
    }

    private fun readWebpDimensions(bytes: ByteArray): Dim? {
        if (bytes.size < 30 || !matchesMagic(bytes, "image/webp")) return null
        val chunk = String(bytes, 12, 4, StandardCharsets.US_ASCII)
        return when (chunk) {
            "VP8X" -> {
                val w = 1 + (bytes[24].toInt() and 0xFF or ((bytes[25].toInt() and 0xFF) shl 8) or ((bytes[26].toInt() and 0xFF) shl 16))
                val h = 1 + (bytes[27].toInt() and 0xFF or ((bytes[28].toInt() and 0xFF) shl 8) or ((bytes[29].toInt() and 0xFF) shl 16))
                Dim(w, h)
            }
            "VP8 " -> {
                val w = ((bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0x3F) shl 8))
                val h = ((bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0x3F) shl 8))
                Dim(w, h)
            }
            "VP8L" -> {
                val bits = (bytes[21].toInt() and 0xFF) or
                    ((bytes[22].toInt() and 0xFF) shl 8) or
                    ((bytes[23].toInt() and 0xFF) shl 16) or
                    ((bytes[24].toInt() and 0xFF) shl 24)
                val w = (bits and 0x3FFF) + 1
                val h = ((bits shr 14) and 0x3FFF) + 1
                Dim(w, h)
            }
            else -> null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private val ENCRYPTED_MAGIC = byteArrayOf('D'.code.toByte(), 'X'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())
    }
}
