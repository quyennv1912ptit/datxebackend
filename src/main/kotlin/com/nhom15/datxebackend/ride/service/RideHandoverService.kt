package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.event.EventPublisher
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.ride.dto.request.ConfirmHandoverRequest
import com.nhom15.datxebackend.ride.dto.request.CreateHandoverReportRequest
import com.nhom15.datxebackend.ride.dto.response.HandoverReportResponse
import com.nhom15.datxebackend.ride.entity.HandoverPhase
import com.nhom15.datxebackend.ride.entity.HandoverPhoto
import com.nhom15.datxebackend.ride.entity.HandoverPhotoAngle
import com.nhom15.datxebackend.ride.entity.VehicleHandoverReport
import com.nhom15.datxebackend.ride.event.PickupHandoverConfirmed
import com.nhom15.datxebackend.ride.repository.HandoverPhotoRepository
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.repository.VehicleHandoverReportRepository
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import com.nhom15.datxebackend.ride.service.state.RideTransitionCommand
import com.nhom15.datxebackend.ride.service.state.RideTransitionEvent
import com.nhom15.datxebackend.ride.service.state.driverRideActor
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RideHandoverService(
    private val rideRepository: RideRepository,
    private val reportRepository: VehicleHandoverReportRepository,
    private val photoRepository: HandoverPhotoRepository,
    private val stateMachine: RideStateMachine,
    private val eventPublisher: EventPublisher,
    private val cloudinaryAssetService: CloudinaryAssetService,
) {
    // Biên bản, ảnh và trạng thái ride phải cùng thành công hoặc cùng rollback.
    @Transactional
    fun createPickup(
        actor: AuthUser,
        rideId: Long,
        request: CreateHandoverReportRequest,
    ): HandoverReportResponse {
        if (actor.role != Role.DRIVER) {
            throw ForbiddenException(message = "Chỉ tài xế được gán mới có thể tạo biên bản nhận xe")
        }
        if (rideId <= 0) {
            throw BadRequestException(message = "rideId phải lớn hơn 0")
        }

        val ride = rideRepository.findById(rideId).orElseThrow {
            NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        }
        if (ride.driverId != actor.id) {
            throw ForbiddenException(message = "Bạn không phải tài xế được gán cho chuyến này")
        }
        if (reportRepository.existsByRideIdAndPhase(rideId, HandoverPhase.PICKUP)) {
            throw ConflictException(message = "Chuyến này đã có biên bản nhận xe")
        }
        if (ride.status != RideStatus.DRIVER_ARRIVED) {
            throw ConflictException(
                ErrorCode.INVALID_RIDE_STATE,
                "Chỉ tạo biên bản nhận xe khi tài xế đã đến điểm đón",
            )
        }
        validateRequest(request, HandoverPhase.PICKUP, rideId)

        val report = VehicleHandoverReport(
            ride = ride,
            phase = HandoverPhase.PICKUP,
            odometer = request.odometer,
            fuelLevel = request.fuelLevel,
            notes = request.notes,
            confirmedByCustomer = false,
        )
        val savedReport = try {
            reportRepository.saveAndFlush(report)
        } catch (_: DataIntegrityViolationException) {
            // Unique ride/phase vẫn bảo vệ nếu hai request cùng vượt qua exists.
            throw ConflictException(message = "Dữ liệu bàn giao bị xung đột, vui lòng tải lại chuyến")
        }

        val photos = mutableListOf<HandoverPhoto>()
        for (photo in request.photos) {
            photos.add(HandoverPhoto(savedReport, photo.angle, photo.fileUrl))
        }
        val savedPhotos = photoRepository.saveAllAndFlush(photos)

        stateMachine.transition(
            RideTransitionCommand(
                rideId = rideId,
                event = RideTransitionEvent.PICKUP_HANDOVER_CREATED,
                actor = driverRideActor(actor.id),
            ),
        )
        return HandoverResponseMapper.toResponse(savedReport, savedPhotos)
    }

    // Lưu trả xe và chuyển trạng thái cùng transaction, giữ nguyên biên bản nhận xe.
    @Transactional
    fun createDropoff(
        actor: AuthUser,
        rideId: Long,
        request: CreateHandoverReportRequest,
    ): HandoverReportResponse {
        if (actor.role != Role.DRIVER) {
            throw ForbiddenException(message = "Chỉ tài xế được gán mới có thể tạo biên bản trả xe")
        }
        if (rideId <= 0) {
            throw BadRequestException(message = "rideId phải lớn hơn 0")
        }

        val ride = rideRepository.findById(rideId).orElseThrow {
            NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        }
        if (ride.driverId != actor.id) {
            throw ForbiddenException(message = "Bạn không phải tài xế được gán cho chuyến này")
        }
        if (reportRepository.existsByRideIdAndPhase(rideId, HandoverPhase.DROPOFF)) {
            throw ConflictException(message = "Chuyến này đã có biên bản trả xe")
        }
        if (ride.status != RideStatus.ARRIVED_DESTINATION) {
            throw ConflictException(
                ErrorCode.INVALID_RIDE_STATE,
                "Chỉ tạo biên bản trả xe khi tài xế đã đến điểm trả",
            )
        }
        validateRequest(request, HandoverPhase.DROPOFF, rideId)

        val pickup = reportRepository.findByRideIdAndPhase(rideId, HandoverPhase.PICKUP)
            ?: throw BadRequestException(
                ErrorCode.HANDOVER_REPORT_REQUIRED,
                "Chưa có biên bản nhận xe để đối chiếu số km",
            )
        if (request.odometer < pickup.odometer) {
            throw BadRequestException(message = "Số km trả xe không được nhỏ hơn số km nhận xe")
        }

        val report = VehicleHandoverReport(
            ride = ride,
            phase = HandoverPhase.DROPOFF,
            odometer = request.odometer,
            fuelLevel = request.fuelLevel,
            notes = request.notes,
            confirmedByCustomer = false,
        )
        val savedReport = try {
            reportRepository.saveAndFlush(report)
        } catch (_: DataIntegrityViolationException) {
            // Unique ride/phase vẫn bảo vệ nếu hai request cùng vượt qua exists.
            throw ConflictException(message = "Dữ liệu bàn giao bị xung đột, vui lòng tải lại chuyến")
        }

        val photos = mutableListOf<HandoverPhoto>()
        for (photo in request.photos) {
            photos.add(HandoverPhoto(savedReport, photo.angle, photo.fileUrl))
        }
        val savedPhotos = photoRepository.saveAllAndFlush(photos)

        stateMachine.transition(
            RideTransitionCommand(
                rideId = rideId,
                event = RideTransitionEvent.DROPOFF_HANDOVER_CREATED,
                actor = driverRideActor(actor.id),
            ),
        )
        return HandoverResponseMapper.toResponse(savedReport, savedPhotos)
    }

    @Transactional
    fun confirmPickup(
        actor: AuthUser,
        rideId: Long,
        request: ConfirmHandoverRequest,
    ): HandoverReportResponse {
        if (actor.role != Role.CUSTOMER) {
            throw ForbiddenException(message = "Chỉ khách hàng sở hữu chuyến mới có thể xác nhận nhận xe")
        }
        if (rideId <= 0) {
            throw BadRequestException(message = "rideId phải lớn hơn 0")
        }
        val ride = rideRepository.findById(rideId).orElseThrow {
            NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        }
        if (ride.customerId != actor.id) {
            throw ForbiddenException(message = "Bạn không phải khách hàng sở hữu chuyến này")
        }
        if (ride.status != RideStatus.VEHICLE_HANDOVER) {
            throw ConflictException(
                ErrorCode.INVALID_RIDE_STATE,
                "Chỉ xác nhận nhận xe khi ride đang ở trạng thái bàn giao xe",
            )
        }
        if (!request.confirmed) {
            throw BadRequestException(message = "Khách hàng phải xác nhận biên bản bàn giao")
        }
        val report = reportRepository.findByRideIdAndPhase(rideId, HandoverPhase.PICKUP)
            ?: throw BadRequestException(
                ErrorCode.HANDOVER_REPORT_REQUIRED,
                "Chưa có biên bản nhận xe của chuyến này",
            )

        // Xác nhận lặp chỉ đọc lại, tránh lưu và đổi updatedAt của biên bản.
        if (!report.confirmedByCustomer) {
            report.confirmedByCustomer = true
            reportRepository.saveAndFlush(report)
            ride.driverId?.let { driverId ->
                eventPublisher.publish(PickupHandoverConfirmed(rideId = rideId, driverId = driverId))
            }
        }
        val photos = photoRepository.findAllByReportIdOrderByCreatedAtAsc(report.id)
        return HandoverResponseMapper.toResponse(report, photos)
    }

    @Transactional
    fun confirmDropoff(actor: AuthUser, rideId: Long, request: ConfirmHandoverRequest): HandoverReportResponse {
        if (actor.role != Role.CUSTOMER) {
            throw ForbiddenException(message = "Chỉ khách hàng sở hữu chuyến mới có thể xác nhận trả xe")
        }
        if (rideId <= 0) throw BadRequestException(message = "rideId phải lớn hơn 0")
        val ride = rideRepository.findById(rideId).orElseThrow { NotFoundException(ErrorCode.RIDE_NOT_FOUND) }
        if (ride.customerId != actor.id) throw NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        if (ride.status !in setOf(RideStatus.VEHICLE_RETURNED, RideStatus.COMPLETED)) {
            throw ConflictException(
                ErrorCode.INVALID_RIDE_STATE,
                "Chỉ xác nhận nhận lại xe khi tài xế đã tạo biên bản trả xe",
            )
        }
        if (!request.confirmed) throw BadRequestException(message = "Khách hàng phải xác nhận biên bản trả xe")
        val report = reportRepository.findByRideIdAndPhase(rideId, HandoverPhase.DROPOFF)
            ?: throw BadRequestException(ErrorCode.HANDOVER_REPORT_REQUIRED, "Chưa có biên bản trả xe của chuyến này")
        if (!report.confirmedByCustomer) {
            report.confirmedByCustomer = true
            reportRepository.saveAndFlush(report)
        }
        val photos = photoRepository.findAllByReportIdOrderByCreatedAtAsc(report.id)
        return HandoverResponseMapper.toResponse(report, photos)
    }

    // Caller trực tiếp không đi qua @Valid của controller, nên service vẫn kiểm tra.
    private fun validateRequest(request: CreateHandoverReportRequest, phase: HandoverPhase, rideId: Long) {
        val handoverName = if (phase == HandoverPhase.PICKUP) "nhận xe" else "trả xe"
        if (request.odometer < 0) {
            throw BadRequestException(message = "Số km $handoverName không được âm")
        }
        val fuelLevel = request.fuelLevel
        if (fuelLevel != null && (fuelLevel < 0 || fuelLevel > 100)) {
            throw BadRequestException(message = "Mức nhiên liệu phải nằm trong khoảng 0 đến 100")
        }
        val notes = request.notes
        if (notes != null && notes.length > 2_000) {
            throw BadRequestException(message = "Ghi chú bàn giao không được vượt quá 2000 ký tự")
        }
        if (request.photos.size < 4 || request.photos.size > 5) {
            throw BadRequestException(message = "Biên bản $handoverName phải có từ 4 đến 5 ảnh")
        }

        val angles = mutableSetOf<HandoverPhotoAngle>()
        for (photo in request.photos) {
            if (!angles.add(photo.angle)) {
                throw BadRequestException(message = "Góc ảnh bàn giao không được trùng")
            }
            if (photo.fileUrl.isBlank() || photo.fileUrl.length > 2_048) {
                throw BadRequestException(message = "Đường dẫn ảnh phải có nội dung và không quá 2048 ký tự")
            }
            cloudinaryAssetService.validateHandoverImage(rideId, photo.fileUrl, photo.publicId)
        }
        val requiredAngles = listOf(
            HandoverPhotoAngle.FRONT, HandoverPhotoAngle.BACK,
            HandoverPhotoAngle.LEFT, HandoverPhotoAngle.RIGHT,
        )
        for (angle in requiredAngles) {
            if (angle !in angles) {
                throw BadRequestException(message = "Biên bản $handoverName thiếu ảnh góc $angle")
            }
        }
    }

}
