package com.nhom15.datxebackend.chat.service

import com.nhom15.datxebackend.chat.entity.Conversation
import com.nhom15.datxebackend.chat.repository.ConversationRepository
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.repository.RideRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ParticipantConversationContext(val ride: Ride, val conversation: Conversation?)

@Service
class ConversationService(
    private val conversationRepository: ConversationRepository,
    private val rideRepository: RideRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun upsert(rideId: Long, customerId: Long, driverId: Long): Conversation {
        jdbcTemplate.update(
            """INSERT INTO conversations (ride_id, customer_id, driver_id, created_at, updated_at)
               VALUES (?, ?, ?, now(), now())
               ON CONFLICT (ride_id) DO UPDATE
               SET customer_id = EXCLUDED.customer_id, driver_id = EXCLUDED.driver_id, updated_at = now()""".trimIndent(),
            rideId,
            customerId,
            driverId,
        )
        return conversationRepository.findByRideId(rideId)
            ?: throw IllegalStateException("Không đọc lại được conversation vừa upsert cho ride $rideId")
    }

    @Transactional
    fun requireParticipant(
        rideId: Long,
        actor: AuthUser,
        lockRide: Boolean = false,
    ): ParticipantConversationContext {
        val ride = if (lockRide) {
            rideRepository.findByIdForUpdate(rideId).orElseThrow { NotFoundException(ErrorCode.RIDE_NOT_FOUND) }
        } else {
            rideRepository.findById(rideId).orElseThrow { NotFoundException(ErrorCode.RIDE_NOT_FOUND) }
        }
        val isParticipant = when (actor.role) {
            Role.CUSTOMER -> ride.customerId == actor.id
            Role.DRIVER -> ride.driverId == actor.id
            Role.ADMIN -> false
        }
        if (!isParticipant) throw NotFoundException(ErrorCode.RIDE_NOT_FOUND)
        return ParticipantConversationContext(ride, conversationRepository.findByRideId(rideId))
    }

    fun requireOpen(context: ParticipantConversationContext): Conversation {
        val conversation = context.conversation
            ?: throw ConflictException(ErrorCode.CHAT_NOT_AVAILABLE)
        if (context.ride.status !in OPEN_CHAT_STATUSES) throw ConflictException(ErrorCode.CHAT_CLOSED)
        return conversation
    }

    fun otherParty(conversation: Conversation, actor: AuthUser) = when (actor.role) {
        Role.CUSTOMER -> com.nhom15.datxebackend.notification.api.Recipient.Driver(conversation.driverId)
        Role.DRIVER -> com.nhom15.datxebackend.notification.api.Recipient.Customer(conversation.customerId)
        Role.ADMIN -> throw NotFoundException(ErrorCode.RIDE_NOT_FOUND)
    }

    private companion object {
        val OPEN_CHAT_STATUSES = setOf(
            RideStatus.DRIVER_ASSIGNED,
            RideStatus.DRIVER_ARRIVING,
            RideStatus.DRIVER_ARRIVED,
            RideStatus.VEHICLE_HANDOVER,
            RideStatus.IN_PROGRESS,
            RideStatus.ARRIVED_DESTINATION,
            RideStatus.VEHICLE_RETURNED,
        )
    }
}
