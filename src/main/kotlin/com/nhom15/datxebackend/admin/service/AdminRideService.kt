package com.nhom15.datxebackend.admin.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.common.redis.RedisKeys
import com.nhom15.datxebackend.common.redis.RedisTtl
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.admin.dto.AdminAssignRideRequest
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import com.nhom15.datxebackend.notification.api.NotificationPort
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.dto.RideUpdatedPayload
import com.nhom15.datxebackend.ride.dto.request.RideListFilter
import com.nhom15.datxebackend.ride.dto.request.CancelRideRequest
import com.nhom15.datxebackend.ride.dto.response.RideDetailResponse
import com.nhom15.datxebackend.ride.dto.response.RideStatusResponse
import com.nhom15.datxebackend.ride.dto.response.RideSummaryResponse
import com.nhom15.datxebackend.ride.repository.ACTIVE_RIDE_STATUSES
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.ride.service.RideService
import com.nhom15.datxebackend.ride.service.RideQueryService
import com.nhom15.datxebackend.ride.service.RideResponseMapper
import com.nhom15.datxebackend.ride.service.state.RideStateMachine
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDate

@Service
class AdminRideService(
    private val rideRepository: RideRepository,
    private val rideQueryService: RideQueryService,
    private val rideService: RideService,
    private val rideStateMachine: RideStateMachine,
    private val driverRepository: DriverRepository,
    private val driverSkillRepository: DriverSkillRepository,
    private val redisTemplate: StringRedisTemplate,
    private val notificationPort: NotificationPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Transactional(readOnly = true)
    fun listRides(actor: AuthUser, filter: RideListFilter): PageResponse<RideSummaryResponse> {
        requireAdmin(actor)
        validatePage(filter)

        val pageable = PageRequest.of(
            filter.page,
            filter.size,
            Sort.by(Sort.Direction.DESC, "createdAt", "id"),
        )
        val statuses = parseStatuses(filter.status)
        val rides = if (statuses == null) {
            rideRepository.findAll(pageable)
        } else {
            rideRepository.findAllByStatusIn(statuses, pageable)
        }
        return PageResponse.from(rides) { ride -> RideResponseMapper.summary(ride) }
    }

    @Transactional(readOnly = true)
    fun getRideDetail(actor: AuthUser, rideId: Long): RideDetailResponse {
        requireAdmin(actor)
        return rideQueryService.getDetail(actor, rideId)
    }

    @Transactional
    fun assignDriver(actor: AuthUser, rideId: Long, request: AdminAssignRideRequest): RideStatusResponse {
        requireAdmin(actor)
        if (rideId <= 0 || request.driverId <= 0) throw BadRequestException(message = "ID chuyến và tài xế phải lớn hơn 0")
        val ride = rideRepository.findByIdForUpdate(rideId).orElseThrow { NotFoundException(ErrorCode.RIDE_NOT_FOUND) }
        val oldDriverId = ride.driverId
        val previousStatus = ride.status
        val disconnectedDriverId = when (previousStatus) {
            RideStatus.NO_DRIVER_FOUND -> oldDriverId?.takeIf(::isDisconnected)
            RideStatus.DRIVER_ASSIGNED, RideStatus.DRIVER_ARRIVING ->
                oldDriverId?.takeIf(::isDisconnected)
                    ?: throw ConflictException(ErrorCode.INVALID_RIDE_STATE, "Chỉ được gán lại khi tài xế hiện tại mất liên lạc")
            else -> throw ConflictException(ErrorCode.INVALID_RIDE_STATE, "Chỉ xử lý chuyến chưa có tài xế hoặc tài xế mất liên lạc")
        }
        if (oldDriverId == request.driverId) {
            throw ConflictException(message = "Tài xế mới phải khác tài xế đang được gán")
        }

        val driverIds = (listOfNotNull(disconnectedDriverId) + request.driverId).distinct().sorted()
        val lockedDrivers = driverIds.mapNotNull { id ->
            driverRepository.findByIdForUpdate(id).orElse(null)?.let { id to it }
        }.toMap()
        val driver = lockedDrivers[request.driverId]
            ?: throw NotFoundException(ErrorCode.USER_NOT_FOUND, "Không tìm thấy tài xế")
        if (driver.status != DriverStatus.ONLINE || driver.verificationStatus != "APPROVED" || driver.isFlagged == true ||
            driver.onlineVehicleType != ride.vehicleType ||
            driver.licenseExpiryDate == null || driver.licenseExpiryDate!!.isBefore(LocalDate.now()) ||
            rideRepository.existsByDriverIdAndStatusIn(request.driverId, ACTIVE_RIDE_STATUSES) ||
            !driverSkillRepository.hasVerifiedSkill(request.driverId, ride.vehicleType, ride.transmission)
        ) {
            throw ConflictException(message = "Tài xế chưa online hoặc không đủ điều kiện nhận loại chuyến này")
        }

        val marker = "admin-assignment:$rideId:${request.driverId}"
        if (reserveDriver(request.driverId, rideId, marker) != 1L) {
            throw ConflictException(message = "Tài xế đang có lời mời hoặc chuyến khác, vui lòng chọn tài xế khác")
        }
        registerReservationCleanup(request.driverId, marker)

        disconnectedDriverId?.let { oldId ->
            lockedDrivers[oldId]?.let { oldDriver ->
                oldDriver.status = DriverStatus.OFFLINE
                oldDriver.onlineVehicleType = null
                driverRepository.save(oldDriver)
            }
        }

        val (assignedRide, fromStatus) = rideStateMachine.adminAssignDriver(rideId, request.driverId, actor.id)
        afterCommit {
            finishRedisAssignment(rideId, request.driverId, disconnectedDriverId, marker)
            runCatching {
                notificationPort.send(
                    Recipient.Driver(request.driverId),
                    RideUpdatedPayload(rideId, RideStatus.DRIVER_ASSIGNED),
                )
            }.onFailure { log.warn("Không gửi được thông báo gán chuyến {} cho tài xế {}", rideId, request.driverId, it) }
        }
        return RideResponseMapper.status(assignedRide, fromStatus)
    }

    @Transactional
    fun cancelRide(actor: AuthUser, rideId: Long, request: CancelRideRequest): RideStatusResponse {
        requireAdmin(actor)
        if (rideId <= 0) throw BadRequestException(message = "rideId phải lớn hơn 0")
        return rideService.cancelRide(actor, rideId, request)
    }

    private fun isDisconnected(driverId: Long): Boolean =
        redisTemplate.hasKey(RedisKeys.driverAlive(driverId.toString())) != true

    private fun reserveDriver(driverId: Long, rideId: Long, marker: String): Long =
        redisTemplate.execute(
            RESERVE_DRIVER_FOR_ADMIN_ASSIGNMENT,
            listOf(
                RedisKeys.driverStatus(driverId.toString()),
                RedisKeys.driverAlive(driverId.toString()),
                RedisKeys.driverCurrentRide(driverId.toString()),
                RedisKeys.driverLock(driverId.toString()),
                RedisKeys.GEO_KEY,
            ),
            marker,
            driverId.toString(),
            rideId.toString(),
            RedisTtl.DRIVER_LOCK.seconds.toString(),
        ) ?: 0L

    private fun registerReservationCleanup(driverId: Long, marker: String) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = Unit

            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) releaseDriverReservation(driverId, marker)
            }
        })
    }

    private fun releaseDriverReservation(driverId: Long, marker: String) {
        runCatching {
            redisTemplate.execute(
                RELEASE_ADMIN_DRIVER_RESERVATION,
                listOf(
                    RedisKeys.driverLock(driverId.toString()),
                    RedisKeys.driverAlive(driverId.toString()),
                    RedisKeys.driverCurrentRide(driverId.toString()),
                    RedisKeys.driverStatus(driverId.toString()),
                    RedisKeys.driverLocation(driverId.toString()),
                    RedisKeys.GEO_KEY,
                ),
                marker,
                driverId.toString(),
            )
        }.onFailure { log.warn("Không giải phóng được khóa tài xế {} sau rollback", driverId, it) }
    }

    private fun finishRedisAssignment(rideId: Long, driverId: Long, oldDriverId: Long?, marker: String) {
        val result = runCatching {
            redisTemplate.execute(
                COMMIT_ADMIN_RIDE_ASSIGNMENT,
                listOf(
                    RedisKeys.rideState(rideId.toString()),
                    RedisKeys.rideCandidates(rideId.toString()),
                    RedisKeys.rideRejected(rideId.toString()),
                    RedisKeys.GEO_KEY,
                    RedisKeys.driverLock(driverId.toString()),
                    RedisKeys.driverCurrentRide(driverId.toString()),
                    RedisKeys.driverStatus(driverId.toString()),
                    oldDriverId?.let { RedisKeys.driverCurrentRide(it.toString()) } ?: "",
                    oldDriverId?.let { RedisKeys.driverStatus(it.toString()) } ?: "",
                ),
                marker,
                rideId.toString(),
                driverId.toString(),
                oldDriverId?.toString().orEmpty(),
                System.currentTimeMillis().toString(),
                RedisTtl.RIDE_STATE.seconds.toString(),
            )
        }
        result.onFailure { log.error("Không cập nhật được Redis sau khi admin gán chuyến {}", rideId, it) }
        if (result.getOrNull() != 1L) {
            log.error("Reservation Redis đã mất khi hoàn tất gán chuyến {} cho tài xế {}", rideId, driverId)
        }
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }

    private fun requireAdmin(actor: AuthUser) {
        if (actor.role != Role.ADMIN) {
            throw ForbiddenException(message = "Chỉ admin mới được quản lý chuyến")
        }
    }

    private fun validatePage(filter: RideListFilter) {
        if (filter.page < 0) {
            throw BadRequestException(message = "page phải lớn hơn hoặc bằng 0")
        }
        if (filter.size !in 1..100) {
            throw BadRequestException(message = "size phải nằm trong khoảng 1 đến 100")
        }
    }

    private fun parseStatuses(raw: String?): List<RideStatus>? {
        if (raw == null) return null
        val values = raw.split(',').map(String::trim).filter(String::isNotEmpty)
        if (values.isEmpty()) {
            throw BadRequestException(message = "status phải chứa ít nhất một trạng thái")
        }
        return values.distinct().map { value ->
            runCatching { RideStatus.valueOf(value.uppercase()) }.getOrElse {
                throw BadRequestException(message = "Trạng thái chuyến không hợp lệ: $value")
            }
        }
    }

    private companion object {
        val RESERVE_DRIVER_FOR_ADMIN_ASSIGNMENT = DefaultRedisScript<Long>(
            """
            if redis.call('GET', KEYS[1]) ~= 'ONLINE'
               or redis.call('EXISTS', KEYS[2]) ~= 1
               or redis.call('EXISTS', KEYS[3]) == 1
               or redis.call('EXISTS', KEYS[4]) == 1 then return 0 end
            if redis.call('SET', KEYS[4], ARGV[1], 'NX', 'EX', ARGV[4]) == false then return 0 end
            redis.call('ZREM', KEYS[5], ARGV[2])
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        val RELEASE_ADMIN_DRIVER_RESERVATION = DefaultRedisScript<Long>(
            """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('DEL', KEYS[1])
            if redis.call('EXISTS', KEYS[2]) == 1 and redis.call('EXISTS', KEYS[3]) == 0
               and redis.call('GET', KEYS[4]) == 'ONLINE' then
                local lat = redis.call('HGET', KEYS[5], 'lat')
                local lng = redis.call('HGET', KEYS[5], 'lng')
                if lat and lng then redis.call('GEOADD', KEYS[6], lng, lat, ARGV[2]) end
            end
            return 1
            """.trimIndent(),
            Long::class.java,
        )

        val COMMIT_ADMIN_RIDE_ASSIGNMENT = DefaultRedisScript<Long>(
            """
            if redis.call('GET', KEYS[5]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[6], ARGV[2])
            redis.call('SET', KEYS[7], 'ON_TRIP')
            redis.call('DEL', KEYS[5])
            redis.call('ZREM', KEYS[4], ARGV[2])
            if ARGV[4] ~= '' then
                if redis.call('GET', KEYS[8]) == ARGV[3] then redis.call('DEL', KEYS[8]) end
                redis.call('SET', KEYS[9], 'OFFLINE')
                redis.call('ZREM', KEYS[4], ARGV[4])
            end
            redis.call('HSET', KEYS[1], 'status', 'DRIVER_ASSIGNED', 'driverId', ARGV[3], 'acceptedAt', ARGV[5])
            redis.call('HDEL', KEYS[1], 'currentDriver')
            redis.call('DEL', KEYS[2], KEYS[3])
            redis.call('EXPIRE', KEYS[1], ARGV[6])
            redis.call('XADD', 'ride:' .. ARGV[2] .. ':events', '*',
                'event', 'DRIVER_ASSIGNED', 'driverId', ARGV[3], 'timestamp', ARGV[5])
            return 1
            """.trimIndent(),
            Long::class.java,
        )
    }
}
