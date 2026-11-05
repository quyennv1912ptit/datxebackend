package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.exception.*
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.ride.dto.request.CreateHandoverReportRequest
import com.nhom15.datxebackend.ride.dto.response.HandoverReportResponse
import com.nhom15.datxebackend.ride.entity.*
import com.nhom15.datxebackend.ride.repository.*
import com.nhom15.datxebackend.support.SupportImage
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant

data class HandoverCorrectionRequest(val revision: Int, val reason: String, val images: List<SupportImage> = emptyList())
data class ReviseHandoverRequest(val revision: Int, val report: CreateHandoverReportRequest)
data class HandoverRevisionResponse(val revision: Int, val report: HandoverReportResponse, val requestedAt: Instant)

@Service
class HandoverCorrectionService(
    private val rides: RideRepository, private val reports: VehicleHandoverReportRepository,
    private val photos: HandoverPhotoRepository, private val validation: RideHandoverService,
    private val assets: CloudinaryAssetService, private val jdbc: JdbcTemplate, private val mapper: ObjectMapper,
) {
    @Transactional
    fun request(actor: AuthUser, rideId: Long, phase: HandoverPhase, request: HandoverCorrectionRequest): HandoverReportResponse {
        if (actor.role != Role.CUSTOMER) throw ForbiddenException()
        val report = owned(actor, rideId, phase, true)
        checkRevision(report, request.revision)
        if (request.reason.trim().length !in 10..2000 || request.images.size > 5) throw BadRequestException(message = "Lý do cần 10–2000 ký tự, tối đa 5 ảnh")
        if (report.confirmedByCustomer) throw ConflictException(message = "Biên bản đã xác nhận; hãy gửi yêu cầu hỗ trợ")
        request.images.forEach { assets.validateCustomerImage(actor, it.fileUrl, it.publicId) }
        if (report.correctionReason != null) {
            if (report.correctionReason != request.reason.trim()) throw ConflictException(message = "Đã có yêu cầu điều chỉnh, hãy chờ tài xế gửi lại")
            return response(report)
        }
        report.correctionReason = request.reason.trim()
        report.evidenceJson = mapper.writeValueAsString(request.images)
        reports.saveAndFlush(report)
        return response(report)
    }

    @Transactional
    fun revise(actor: AuthUser, rideId: Long, phase: HandoverPhase, request: ReviseHandoverRequest): HandoverReportResponse {
        if (actor.role != Role.DRIVER) throw ForbiddenException()
        val report = owned(actor, rideId, phase, true)
        checkRevision(report, request.revision)
        if (report.confirmedByCustomer || report.correctionReason == null) throw ConflictException(message = "Chỉ sửa khi khách đã yêu cầu điều chỉnh")
        validation.validateRequest(request.report, phase, rideId)
        if (phase == HandoverPhase.DROPOFF && request.report.odometer < (reports.findByRideIdAndPhase(rideId, HandoverPhase.PICKUP)?.odometer ?: 0))
            throw BadRequestException(message = "Số km trả xe không được nhỏ hơn số km nhận xe")
        jdbc.update("INSERT INTO handover_revisions(report_id,revision,snapshot_json,correction_reason,evidence_json) VALUES (?,?,?,?,?)",
            report.id, report.revision, mapper.writeValueAsString(response(report)), report.correctionReason, report.evidenceJson)
        photos.deleteAll(photos.findAllByReportIdOrderByCreatedAtAsc(report.id))
        photos.flush()
        report.odometer = request.report.odometer
        report.fuelLevel = request.report.fuelLevel
        report.notes = request.report.notes
        report.revision++
        report.correctionReason = null
        report.evidenceJson = "[]"
        reports.saveAndFlush(report)
        photos.saveAllAndFlush(request.report.photos.map { HandoverPhoto(report, it.angle, it.fileUrl) })
        return response(report)
    }

    @Transactional(readOnly = true)
    fun history(actor: AuthUser, rideId: Long, phase: HandoverPhase): List<HandoverRevisionResponse> {
        val report = owned(actor, rideId, phase, false)
        return jdbc.query("SELECT revision,snapshot_json,created_at FROM handover_revisions WHERE report_id=? ORDER BY revision DESC",
            { row, _ -> HandoverRevisionResponse(row.getInt(1), mapper.readValue(row.getString(2), HandoverReportResponse::class.java), row.getTimestamp(3).toInstant()) }, report.id)
    }

    private fun owned(actor: AuthUser, rideId: Long, phase: HandoverPhase, editable: Boolean): VehicleHandoverReport {
        // Serialize edits against ride progression, and always acquire ride before report.
        val ride = (if (editable) rides.findByIdForUpdate(rideId) else rides.findById(rideId)).orElseThrow { NotFoundException() }
        if (actor.role != Role.ADMIN && !(actor.role == Role.CUSTOMER && ride.customerId == actor.id) && !(actor.role == Role.DRIVER && ride.driverId == actor.id)) throw NotFoundException()
        if (editable && ride.status != if (phase == HandoverPhase.PICKUP) RideStatus.VEHICLE_HANDOVER else RideStatus.VEHICLE_RETURNED)
            throw ConflictException(message = "Chuyến đã qua bước bàn giao; hãy gửi yêu cầu hỗ trợ")
        return reports.findByRideIdAndPhase(rideId, phase) ?: throw NotFoundException()
    }
    private fun checkRevision(report: VehicleHandoverReport, revision: Int) {
        if (revision != report.revision) throw ConflictException(message = "Biên bản đã có phiên bản mới, hãy tải lại")
    }
    private fun response(report: VehicleHandoverReport) = HandoverResponseMapper.toResponse(report, photos.findAllByReportIdOrderByCreatedAtAsc(report.id))
}
