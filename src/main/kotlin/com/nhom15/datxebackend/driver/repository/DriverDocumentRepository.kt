package com.nhom15.datxebackend.driver.repository

import com.nhom15.datxebackend.driver.dto.DocumentVerificationStatus
import com.nhom15.datxebackend.driver.dto.DriverDocumentResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

@Repository
class DriverDocumentRepository(private val jdbcTemplate: JdbcTemplate) {
    fun markNotCurrent(driverId: Long, documentType: String): Int = jdbcTemplate.update(
        "UPDATE driver_documents SET is_current = false, updated_at = CURRENT_TIMESTAMP WHERE driver_id = ? AND document_type = ? AND is_current = true",
        driverId,
        documentType,
    )

    fun insert(
        driverId: Long,
        documentType: String,
        documentNumber: String?,
        secureUrl: String,
        publicId: String,
        expiresAt: LocalDate?,
    ): DriverDocumentResponse {
        val id = jdbcTemplate.queryForObject(
            """INSERT INTO driver_documents
               (driver_id, document_type, document_number, secure_url, cloudinary_public_id, resource_type, verification_status, expires_at, is_current)
               VALUES (?, ?, ?, ?, ?, 'image', 'PENDING', ?, true)
               RETURNING id""".trimIndent(),
            Long::class.java,
            driverId,
            documentType,
            documentNumber,
            secureUrl,
            publicId,
            expiresAt?.let(java.sql.Date::valueOf),
        ) ?: throw IllegalStateException("Không lấy được id giấy tờ vừa nộp")
        return findById(id) ?: throw IllegalStateException("Không đọc lại được giấy tờ vừa nộp")
    }

    fun findCurrentForDriver(driverId: Long): List<DriverDocumentResponse> = jdbcTemplate.query(
        "SELECT * FROM driver_documents WHERE driver_id = ? AND is_current = true ORDER BY document_type, created_at DESC, id DESC",
        { row, _ -> row.toDocument() },
        driverId,
    )

    fun findAllForDriver(driverId: Long): List<DriverDocumentResponse> = jdbcTemplate.query(
        "SELECT * FROM driver_documents WHERE driver_id = ? ORDER BY is_current DESC, created_at DESC, id DESC",
        { row, _ -> row.toDocument() },
        driverId,
    )

    fun findById(id: Long): DriverDocumentResponse? = jdbcTemplate.query(
        "SELECT * FROM driver_documents WHERE id = ?",
        { row, _ -> row.toDocument() },
        id,
    ).firstOrNull()

    fun review(
        id: Long,
        status: DocumentVerificationStatus,
        rejectionReason: String?,
        adminId: Long,
        now: Instant,
    ): Int = jdbcTemplate.update(
        """UPDATE driver_documents
           SET verification_status = ?, rejection_reason = ?, reviewed_at = ?, reviewed_by_admin_id = ?, updated_at = CURRENT_TIMESTAMP
           WHERE id = ? AND is_current = true""".trimIndent(),
        status.name,
        rejectionReason,
        Timestamp.from(now),
        adminId,
        id,
    )

    fun findReviewQueue(
        status: DocumentVerificationStatus,
        limit: Int,
        offset: Long,
    ): List<DriverDocumentQueueRow> = jdbcTemplate.query(
        """SELECT document.*, driver.full_name AS driver_name, driver.phone_number AS driver_phone
           FROM driver_documents document
           JOIN drivers driver ON driver.id = document.driver_id
           WHERE document.is_current = true AND document.verification_status = ?
           ORDER BY document.created_at, document.id
           LIMIT ? OFFSET ?""".trimIndent(),
        { row, _ ->
            DriverDocumentQueueRow(
                document = row.toDocument(),
                driverName = row.getString("driver_name"),
                driverPhone = row.getString("driver_phone"),
            )
        },
        status.name,
        limit,
        offset,
    )

    fun countReviewQueue(status: DocumentVerificationStatus): Long = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM driver_documents WHERE is_current = true AND verification_status = ?",
        Long::class.java,
        status.name,
    ) ?: 0L

    private fun ResultSet.toDocument() = DriverDocumentResponse(
        id = getLong("id"),
        driverId = getLong("driver_id"),
        documentType = getString("document_type"),
        documentNumber = getString("document_number"),
        secureUrl = getString("secure_url"),
        publicId = getString("cloudinary_public_id"),
        verificationStatus = DocumentVerificationStatus.valueOf(getString("verification_status")),
        rejectionReason = getString("rejection_reason"),
        expiresAt = getDate("expires_at")?.toLocalDate(),
        isCurrent = getBoolean("is_current"),
        reviewedAt = getTimestamp("reviewed_at")?.toInstant(),
        createdAt = getTimestamp("created_at").toInstant(),
    )
}

data class DriverDocumentQueueRow(
    val document: DriverDocumentResponse,
    val driverName: String?,
    val driverPhone: String,
)
