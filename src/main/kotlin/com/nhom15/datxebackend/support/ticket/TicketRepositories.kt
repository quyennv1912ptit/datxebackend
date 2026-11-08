package com.nhom15.datxebackend.support.ticket

import com.nhom15.datxebackend.common.enums.Role
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface SupportTicketRepository : JpaRepository<SupportTicketEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SupportTicketEntity t where t.id = :id")
    fun findForUpdate(id: Long): SupportTicketEntity?

    fun findByIdAndOwnerIdAndOwnerRole(id: Long, ownerId: Long, ownerRole: Role): SupportTicketEntity?

    fun findByOwnerIdAndOwnerRoleOrderByUpdatedAtDescIdDesc(
        ownerId: Long,
        ownerRole: Role,
        pageable: Pageable,
    ): Page<SupportTicketEntity>
}

interface TicketMessageRepository : JpaRepository<TicketMessageEntity, Long> {
    fun findByTicketIdOrderByCreatedAtAscIdAsc(ticketId: Long): List<TicketMessageEntity>
}
