package com.nhom15.datxebackend.driver.verification

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Periodically removes orphan eKYC capture files left on disk after an abrupt process crash
 * before DB commit. Only files older than [minAge] and unreferenced by any row in
 * `driver_verification_captures` are eligible; safe for concurrent/repeated execution.
 */
@Component
class VerificationOrphanCleanupWorker(
    private val workflowService: DriverVerificationWorkflowService,
    @Value("\${app.verification.orphan-cleanup.enabled:true}")
    private val enabled: Boolean = true,
    @Value("\${app.verification.orphan-cleanup.min-age:1h}")
    private val minAge: Duration = Duration.ofHours(1),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${app.verification.orphan-cleanup.interval-ms:3600000}",
        initialDelayString = "\${app.verification.orphan-cleanup.initial-delay-ms:300000}",
    )
    fun sweepOrphanCaptures(): Int {
        if (!enabled) return 0
        val effectiveMinAge = if (minAge < MIN_SAFE_AGE) MIN_SAFE_AGE else minAge
        return try {
            val removed = workflowService.reconcileOrphanCaptures(effectiveMinAge)
            if (removed > 0) {
                log.info("Đã dọn {} tệp capture eKYC mồ côi quá hạn", removed)
            }
            removed
        } catch (ex: Exception) {
            log.warn("Không thể chạy đối soát dọn tệp capture eKYC mồ côi: {}", ex.message)
            0
        }
    }

    companion object {
        val MIN_SAFE_AGE: Duration = Duration.ofMinutes(5)
    }
}
