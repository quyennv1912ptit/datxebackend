package com.nhom15.datxebackend.auth.service

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.uploads.CloudinaryAssetService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class AccountCleanupOutboxWorker @Autowired constructor(
    private val outbox: AccountCleanupOutboxRepository,
    private val firebaseAuth: FirebaseAuth,
    private val redisTemplate: StringRedisTemplate,
    private val cloudinaryAssetService: CloudinaryAssetService,
    private val customerRepository: CustomerRepository? = null,
    private val driverRepository: DriverRepository? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.outbox.poll-interval-ms:1000}")
    fun processPendingCleanup() {
        outbox.claimDue().forEach { task ->
            try {
                if (shouldDeleteFirebaseUser(task.firebaseUid)) {
                    deleteFirebaseUser(task.firebaseUid)
                }
                task.cloudinaryPublicIds.forEach(cloudinaryAssetService::deleteNowOrThrow)
                if (task.ownerRole == "DRIVER") clearDriverPresence(task.ownerId)
                else redisTemplate.delete(RedisKeys.customerActiveRide(task.ownerId.toString()))
                outbox.markCompleted(task.id)
            } catch (exception: Exception) {
                log.error("Không hoàn tất được cleanup tài khoản {}:{}; sẽ thử lại", task.ownerRole, task.ownerId, exception)
                runCatching { outbox.retry(task.id, exception.message ?: exception.javaClass.simpleName) }
                    .onFailure { log.error("Không lên lịch retry cleanup {}", task.id, it) }
            }
        }
    }

    private fun shouldDeleteFirebaseUser(firebaseUid: String): Boolean {
        if (firebaseUid.isBlank()) return false
        val hasActiveCustomer = customerRepository?.findByFirebaseUid(firebaseUid)
            ?.map { it.deletedAt == null }
            ?.orElse(false) == true
        val hasActiveDriver = driverRepository?.findByFirebaseUid(firebaseUid)
            ?.map { it.deletedAt == null }
            ?.orElse(false) == true
        return !hasActiveCustomer && !hasActiveDriver
    }

    private fun deleteFirebaseUser(firebaseUid: String) {
        try {
            firebaseAuth.deleteUser(firebaseUid)
        } catch (exception: FirebaseAuthException) {
            if (exception.authErrorCode?.name != "USER_NOT_FOUND") throw exception
        }
    }

    private fun clearDriverPresence(driverId: Long) {
        val id = driverId.toString()
        redisTemplate.opsForGeo().remove(RedisKeys.GEO_KEY, id)
        redisTemplate.opsForZSet().remove(RedisKeys.DRIVERS_LASTSEEN, id)
        redisTemplate.delete(
            listOf(
                RedisKeys.driverAlive(id),
                RedisKeys.driverStatus(id),
                RedisKeys.driverLocation(id),
                RedisKeys.driverLock(id),
                RedisKeys.driverCurrentRide(id),
                RedisKeys.driverStats(id),
            ),
        )
    }
}
