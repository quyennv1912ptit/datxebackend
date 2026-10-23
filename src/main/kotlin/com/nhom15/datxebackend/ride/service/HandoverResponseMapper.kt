package com.nhom15.datxebackend.ride.service

import com.nhom15.datxebackend.ride.dto.response.HandoverPhotoResponse
import com.nhom15.datxebackend.ride.dto.response.HandoverReportResponse
import com.nhom15.datxebackend.ride.entity.HandoverPhoto
import com.nhom15.datxebackend.ride.entity.VehicleHandoverReport

internal object HandoverResponseMapper {
    fun toResponse(report: VehicleHandoverReport, photos: List<HandoverPhoto>): HandoverReportResponse {
        val photoResponses = mutableListOf<HandoverPhotoResponse>()
        for (photo in photos) {
            photoResponses.add(HandoverPhotoResponse(photo.id, photo.angle, photo.fileUrl, photo.createdAt))
        }
        return HandoverReportResponse(
            id = report.id,
            phase = report.phase,
            odometer = report.odometer,
            fuelLevel = report.fuelLevel,
            notes = report.notes,
            confirmedByCustomer = report.confirmedByCustomer,
            photos = photoResponses,
            createdAt = report.createdAt,
        )
    }
}
