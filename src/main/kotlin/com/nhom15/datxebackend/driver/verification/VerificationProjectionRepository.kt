package com.nhom15.datxebackend.driver.verification

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.SqlParameterValue
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Types
import java.time.LocalDate
import java.util.UUID

@Repository
class VerificationProjectionRepository @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    @Value("\${app.verification-sync.enabled:false}") private val requireSyncCheckpoint: Boolean = true,
) {
    constructor(jdbc: JdbcTemplate, mapper: ObjectMapper) : this(jdbc, mapper, true)

    fun cursor(): Long = checkpoint().cursor

    fun synchronizedHead(): VerificationHead? = jdbc.query(
        if (requireSyncCheckpoint) {
            """SELECT stream_id, cursor_seq FROM verification_sync_checkpoint
               WHERE singleton AND caught_up_at > clock_timestamp() - interval '10 seconds'"""
        } else {
            """SELECT stream_id, cursor_seq FROM verification_sync_checkpoint WHERE singleton"""
        },
        { rs, _ -> VerificationHead(rs.getObject("stream_id", UUID::class.java), rs.getLong("cursor_seq")) },
    ).firstOrNull()

    private data class Checkpoint(val cursor: Long, val stream: UUID?)
    private fun checkpoint(lock: Boolean = false): Checkpoint = jdbc.query(
        "SELECT cursor_seq, stream_id FROM verification_sync_checkpoint WHERE singleton" +
            if (lock) " FOR UPDATE" else "",
        { rs, _ -> Checkpoint(rs.getLong("cursor_seq"), rs.getObject("stream_id", UUID::class.java)) },
    ).single()

    @Transactional
    fun applyPage(start: Long, feed: VerificationFeed) {
        val current = checkpoint(lock = true)
        if (current.cursor != start) return
        require(current.stream == null || current.stream == feed.streamId) { "Verification stream changed" }
        require(feed.head >= start)
        val owners = feed.events.map { it.ownerUid }.distinct()
        if (owners.isNotEmpty()) {
            val marks = owners.joinToString(",") { "?" }
            jdbc.queryForList("SELECT id FROM drivers WHERE firebase_uid IN ($marks) ORDER BY id FOR UPDATE", *owners.toTypedArray())
        }
        var cursor = start
        val today = capabilityToday()
        for (event in feed.events) {
            require(event.seq > cursor && event.seq <= feed.head && event.revision >= 0)
            require(event.ownerUid.isNotBlank() && event.ownerUid.length <= 128)
            require(event.entitlements.size <= 20)
            val existingApplication = jdbc.query(
                "SELECT application_id FROM driver_verification_projection WHERE owner_uid=?",
                { rs, _ -> rs.getObject(1, UUID::class.java) }, event.ownerUid,
            ).firstOrNull()
            require(existingApplication == null || existingApplication == event.applicationId)
            jdbc.update(
                """INSERT INTO driver_verification_projection
                   (owner_uid, application_id, revision, status, entitlements, cccd_expires_on, approved_capabilities)
                   VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (owner_uid) DO UPDATE
                   SET revision = EXCLUDED.revision, status = EXCLUDED.status,
                       entitlements = EXCLUDED.entitlements, cccd_expires_on = EXCLUDED.cccd_expires_on,
                       approved_capabilities = EXCLUDED.approved_capabilities
                   WHERE driver_verification_projection.application_id = EXCLUDED.application_id
                     AND driver_verification_projection.revision < EXCLUDED.revision""",
                event.ownerUid, event.applicationId, event.revision, event.status.name,
                mapper.writeValueAsString(event.entitlements), event.cccdExpiresOn,
                mapper.writeValueAsString(grants(event, today)),
            )
            cursor = event.seq
        }
        require(if (feed.hasMore) cursor > start && cursor < feed.head else cursor == feed.head)
        jdbc.update(
            """UPDATE verification_sync_checkpoint SET cursor_seq=?, stream_id=?,
               caught_up_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END WHERE singleton""",
            cursor, feed.streamId, !feed.hasMore,
        )
    }

    @Transactional
    fun upsertDirectProjection(
        ownerUid: String,
        applicationId: UUID,
        revision: Long,
        status: VerificationStatus,
        entitlements: List<LicenseEntitlement>,
        cccdExpiresOn: LocalDate?,
        capabilityReview: CapabilityReview?,
        explicitCapabilities: List<ApprovedCapability>? = null,
    ) {
        val today = capabilityToday()
        val event = VerificationEvent(
            seq = revision,
            applicationId = applicationId,
            revision = revision,
            ownerUid = ownerUid,
            status = status,
            entitlements = entitlements,
            cccdExpiresOn = cccdExpiresOn,
            capabilityReview = capabilityReview,
        )
        val granted = explicitCapabilities ?: grants(event, today)
        jdbc.update(
            """INSERT INTO driver_verification_projection
               (owner_uid, application_id, revision, status, entitlements, cccd_expires_on, approved_capabilities)
               VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT (owner_uid) DO UPDATE
               SET application_id = EXCLUDED.application_id,
                   revision = EXCLUDED.revision,
                   status = EXCLUDED.status,
                   entitlements = EXCLUDED.entitlements,
                   cccd_expires_on = EXCLUDED.cccd_expires_on,
                   approved_capabilities = EXCLUDED.approved_capabilities
               WHERE driver_verification_projection.revision <= EXCLUDED.revision""",
            ownerUid,
            applicationId,
            revision,
            status.name,
            mapper.writeValueAsString(entitlements),
            SqlParameterValue(Types.DATE, cccdExpiresOn),
            mapper.writeValueAsString(granted),
        )
        jdbc.update("UPDATE verification_sync_checkpoint SET caught_up_at = clock_timestamp() WHERE singleton")
    }

    fun failed() {
        jdbc.update("UPDATE verification_sync_checkpoint SET caught_up_at=NULL WHERE singleton")
    }

    fun current(ownerUid: String): DriverVerificationProjection? = find("p.owner_uid", ownerUid)
    fun forApplication(applicationId: UUID): DriverVerificationProjection? = find("p.application_id", applicationId)

    fun savePreferences(driverId: Long, revision: Long, nextVersion: Long, enabled: Set<String>) {
        jdbc.update(
            """INSERT INTO driver_capability_preferences (driver_id, approval_revision, version, enabled_groups)
               VALUES (?,?,?,?) ON CONFLICT (driver_id) DO UPDATE SET
               approval_revision=EXCLUDED.approval_revision, version=EXCLUDED.version, enabled_groups=EXCLUDED.enabled_groups""",
            driverId, revision, nextVersion, mapper.writeValueAsString(enabled.sorted()),
        )
    }

    /**
     * Preserves previously enabled capability groups that remain approved after re-verification.
     */
    fun reconcilePreferences(ownerUid: String, newRevision: Long, approvedGroupIds: Set<String>) {
        val row = jdbc.query(
            """SELECT d.id, prefs.version, prefs.enabled_groups
               FROM drivers d
               JOIN driver_capability_preferences prefs ON prefs.driver_id = d.id
               WHERE d.firebase_uid = ?""",
            { rs, _ ->
                Triple(
                    rs.getLong("id"),
                    rs.getLong("version"),
                    rs.getString("enabled_groups"),
                )
            },
            ownerUid,
        ).firstOrNull() ?: return
        val (driverId, version, rawEnabled) = row
        val factory = mapper.typeFactory
        val priorEnabled: List<String> = mapper.readValue(
            rawEnabled ?: "[]",
            factory.constructCollectionType(List::class.java, String::class.java),
        )
        val preserved = priorEnabled.filter { it in approvedGroupIds }.distinct().sorted()
        jdbc.update(
            """UPDATE driver_capability_preferences
               SET approval_revision = ?, version = ?, enabled_groups = ?
               WHERE driver_id = ?""",
            newRevision,
            version + 1L,
            mapper.writeValueAsString(preserved),
            driverId,
        )
    }

    private fun find(column: String, value: Any): DriverVerificationProjection? {
        val freshnessClause = if (requireSyncCheckpoint) {
            " AND c.caught_up_at > clock_timestamp() - interval '10 seconds'"
        } else {
            ""
        }
        return jdbc.query(
            """SELECT p.status, p.revision, p.entitlements, p.cccd_expires_on, p.approved_capabilities,
                      prefs.version, prefs.enabled_groups
               FROM driver_verification_projection p
               CROSS JOIN verification_sync_checkpoint c
               LEFT JOIN drivers d ON d.firebase_uid=p.owner_uid
               LEFT JOIN driver_capability_preferences prefs ON prefs.driver_id=d.id AND prefs.approval_revision=p.revision
               WHERE $column=?$freshnessClause""",
            { rs, _ ->
                val factory = mapper.typeFactory
                fun <T> readList(columnName: String, element: Class<T>): List<T> =
                    mapper.readValue(
                        rs.getString(columnName) ?: "[]",
                        factory.constructCollectionType(List::class.java, element),
                    )
                DriverVerificationProjection(
                    status = VerificationStatus.valueOf(rs.getString("status")),
                    entitlements = readList("entitlements", LicenseEntitlement::class.java),
                    cccdExpiresOn = rs.getObject("cccd_expires_on", LocalDate::class.java),
                    revision = rs.getLong("revision"),
                    capabilities = readList("approved_capabilities", ApprovedCapability::class.java),
                    enabledGroups = readList("enabled_groups", String::class.java).toSet(),
                    preferenceVersion = rs.getLong("version"),
                )
            },
            value,
        ).firstOrNull()
    }
}
