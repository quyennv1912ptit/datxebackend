package com.nhom15.datxebackend.ride.entity

import com.nhom15.datxebackend.common.entity.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

@Entity
@Table(name = "handover_photos", indexes = [Index(name = "idx_handover_photos_report", columnList = "report_id")])
class HandoverPhoto(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false, foreignKey = ForeignKey(name = "fk6wrid0n5ixwlnrr1c29xggr4g"))
    var report: VehicleHandoverReport,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var angle: HandoverPhotoAngle,

    @Column(name = "file_url", nullable = false, columnDefinition = "text")
    var fileUrl: String,
) : BaseEntity()
