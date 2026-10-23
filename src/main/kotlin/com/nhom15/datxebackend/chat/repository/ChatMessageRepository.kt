package com.nhom15.datxebackend.chat.repository

import com.nhom15.datxebackend.chat.entity.ChatMessage
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    fun findByConversationIdAndClientMessageId(conversationId: Long, clientMessageId: UUID): ChatMessage?

    @Query("select m from ChatMessage m where m.conversationId = :cid order by m.id desc")
    fun latest(@Param("cid") conversationId: Long, pageable: Pageable): List<ChatMessage>

    @Query("select m from ChatMessage m where m.conversationId = :cid and m.id < :before order by m.id desc")
    fun before(
        @Param("cid") conversationId: Long,
        @Param("before") beforeId: Long,
        pageable: Pageable,
    ): List<ChatMessage>

    @Query("select m from ChatMessage m where m.conversationId = :cid and m.id > :after order by m.id asc")
    fun after(
        @Param("cid") conversationId: Long,
        @Param("after") afterId: Long,
        pageable: Pageable,
    ): List<ChatMessage>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """update ChatMessage m set m.readAt = :now
           where m.conversationId = :cid and m.readAt is null
             and m.senderRole <> :me and m.id <= :upTo""",
    )
    fun markRead(
        @Param("cid") conversationId: Long,
        @Param("me") senderRole: String,
        @Param("upTo") upToMessageId: Long,
        @Param("now") now: Instant,
    ): Int

    @Query(
        "select count(m) from ChatMessage m where m.conversationId = :cid and m.readAt is null and m.senderRole <> :me",
    )
    fun countUnreadFor(@Param("cid") conversationId: Long, @Param("me") senderRole: String): Long
}
