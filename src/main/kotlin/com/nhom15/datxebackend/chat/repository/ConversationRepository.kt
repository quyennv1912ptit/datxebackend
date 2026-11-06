package com.nhom15.datxebackend.chat.repository

import com.nhom15.datxebackend.chat.entity.Conversation
import org.springframework.data.jpa.repository.JpaRepository

interface ConversationRepository : JpaRepository<Conversation, Long> {
    fun findByRideIdAndDriverId(rideId: Long, driverId: Long): Conversation?
    fun findFirstByRideIdOrderByIdDesc(rideId: Long): Conversation?
}
