package com.nhom15.datxebackend.chat.repository

import com.nhom15.datxebackend.chat.entity.Conversation
import org.springframework.data.jpa.repository.JpaRepository

interface ConversationRepository : JpaRepository<Conversation, Long> {
    fun findByRideIdAndDriverId(rideId: Long, driverId: Long): Conversation?
    fun existsByRideIdAndDriverId(rideId: Long, driverId: Long): Boolean
    fun findFirstByRideIdOrderByIdDesc(rideId: Long): Conversation?
    /** V17 keeps one thread per driver on a redispatched ride; admin needs all of them. */
    fun findAllByRideIdOrderByIdDesc(rideId: Long): List<Conversation>
}
