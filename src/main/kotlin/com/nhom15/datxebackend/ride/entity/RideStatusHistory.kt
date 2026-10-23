package com.nhom15.datxebackend.ride.entity

import com.nhom15.datxebackend.common.entity.BaseEntity
import com.nhom15.datxebackend.common.enums.RideStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.ForeignKey
import jakarta.persistence.Index

@Entity
@Table(name = "ride_status_histories", indexes = [
    Index(name = "idx_ride_status_histories_ride", columnList = "ride_id,created_at"),
])
class RideStatusHistory(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ride_id", nullable = false, foreignKey = ForeignKey(name = "fk74yfwrl9bakafaakiakjo9vh5"))
    var ride: Ride,

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    var fromStatus: RideStatus? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    var toStatus: RideStatus,

    @Column(name = "actor_id")
    var actorId: Long? = null,

    @Column(columnDefinition = "text")
    var note: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", length = 20)
    var actorRole: RideActorRole? = null,
) : BaseEntity()
