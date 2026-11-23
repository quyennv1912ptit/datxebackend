package com.nhom15.datxebackend.chat.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "conversations")
class Conversation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    // V17 allows one thread per driver on a redispatched ride (uq ride_id, driver_id).
    @Column(name = "ride_id", nullable = false)
    var rideId: Long = 0

    @Column(name = "customer_id", nullable = false)
    var customerId: Long = 0

    @Column(name = "driver_id", nullable = false)
    var driverId: Long = 0

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}
