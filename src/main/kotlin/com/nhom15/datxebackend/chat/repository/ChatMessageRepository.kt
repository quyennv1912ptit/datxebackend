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

    /**
     * Ride-wide read with the visibility rule applied by the database, before paging.
     * A caller passes `me = ""` to see every message; a current driver passes "DRIVER"
     * to see his own thread plus customer messages left in preserved threads, and a
     * former driver is given only his own thread id.
     */
    @Query(
        """select m from ChatMessage m
           where m.conversationId in :cids
             and (m.conversationId = :ownCid or :me = '' or m.senderRole <> :me)
           order by m.id desc""",
    )
    fun latestVisible(
        @Param("cids") conversationIds: Collection<Long>,
        @Param("ownCid") ownConversationId: Long,
        @Param("me") senderRole: String,
        pageable: Pageable,
    ): List<ChatMessage>

    @Query(
        """select m from ChatMessage m
           where m.conversationId in :cids
             and (m.conversationId = :ownCid or :me = '' or m.senderRole <> :me)
             and m.id < :before
           order by m.id desc""",
    )
    fun beforeVisible(
        @Param("cids") conversationIds: Collection<Long>,
        @Param("ownCid") ownConversationId: Long,
        @Param("me") senderRole: String,
        @Param("before") beforeId: Long,
        pageable: Pageable,
    ): List<ChatMessage>

    @Query(
        """select m from ChatMessage m
           where m.conversationId in :cids
             and (m.conversationId = :ownCid or :me = '' or m.senderRole <> :me)
             and m.id > :after
           order by m.id asc""",
    )
    fun afterVisible(
        @Param("cids") conversationIds: Collection<Long>,
        @Param("ownCid") ownConversationId: Long,
        @Param("me") senderRole: String,
        @Param("after") afterId: Long,
        pageable: Pageable,
    ): List<ChatMessage>

    /** Newest message id that actually belongs to this thread; used to bound markRead. */
    @Query("select max(m.id) from ChatMessage m where m.conversationId = :cid")
    fun findMaxIdInConversation(@Param("cid") conversationId: Long): Long?

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
