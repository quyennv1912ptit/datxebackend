package com.nhom15.datxebackend.ride.repository

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.ride.entity.Ride
import jakarta.persistence.LockModeType
import jakarta.transaction.Transactional
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
- Chuyến đang diễn ra: từ lúc tìm tài xế đến khi trả xe, chưa COMPLETED.
- Không gồm PENDING (chuyến đặt lịch chưa đến giờ không được chặn khách đặt chuyến khác)
- và không gồm các trạng thái kết thúc.
 */
val ACTIVE_RIDE_STATUSES: List<RideStatus> = listOf(
    RideStatus.SEARCHING,
    RideStatus.DRIVER_ASSIGNED,
    RideStatus.DRIVER_ARRIVING,
    RideStatus.DRIVER_ARRIVED,
    RideStatus.VEHICLE_HANDOVER,
    RideStatus.IN_PROGRESS,
    RideStatus.ARRIVED_DESTINATION,
    RideStatus.VEHICLE_RETURNED
)

@Repository
interface RideRepository : JpaRepository<Ride, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ride from Ride ride where ride.id = :rideId")
    fun findByIdForUpdate(@Param("rideId") rideId: Long): java.util.Optional<Ride>

    // =========================================================================
    // 1. NHÓM HÀM THEO DÕI CHUYẾN ĐANG DIỄN RA (Chặn đặt trùng)
    // =========================================================================

    fun findFirstByCustomerIdAndStatusInOrderByCreatedAtDesc(
        customerId: Long,
        statuses: Collection<RideStatus>
    ): Ride?

    fun existsByCustomerIdAndStatusIn(
        customerId: Long,
        statuses: Collection<RideStatus>
    ): Boolean

    fun existsByCustomerIdAndStatusNotIn(
        customerId: Long,
        statuses: Collection<RideStatus>
    ): Boolean

    fun findFirstByDriverIdAndStatusInOrderByCreatedAtDesc(
        driverId: Long,
        statuses: Collection<RideStatus>
    ): Ride?

    fun existsByDriverIdAndStatusNotIn(
        driverId: Long,
        statuses: Collection<RideStatus>
    ): Boolean

    fun existsByDriverIdAndStatusIn(driverId: Long, statuses: Collection<RideStatus>): Boolean

    // =========================================================================
    // 2. NHÓM HÀM CHO CRON JOB (Quét dọn dẹp hệ thống)
    // =========================================================================

    /** Chuyến kẹt ở một trạng thái quá lâu (ví dụ SEARCHING mà dispatch đã bị mất). */
    fun findByStatusAndUpdatedAtBefore(status: RideStatus, cutoff: Instant): List<Ride>

    /** Chuyến đặt ngay (không có lịch) kẹt ở PENDING: đã lưu DB nhưng event dispatch bị mất. */
    fun findByStatusAndScheduledAtIsNullAndUpdatedAtBefore(status: RideStatus, cutoff: Instant): List<Ride>

    /** Chuyến đặt lịch đã đến lúc xử lý (gần giờ đón để dispatch, hoặc quá giờ để chuyển EXPIRED). */
    fun findByStatusAndScheduledAtLessThanEqual(status: RideStatus, time: Instant): List<Ride>

    // =========================================================================
    // 3. NHÓM HÀM TRUY VẤN LỊCH SỬ VÀ PHÂN TRANG (API)
    // =========================================================================

    fun findByIdAndCustomerId(id: Long, customerId: Long): Ride?

    fun findByIdAndDriverId(id: Long, driverId: Long): Ride?

    fun findAllByCustomerId(customerId: Long, pageable: Pageable): Page<Ride>

    fun findByCustomerIdOrderByCreatedAtDesc(customerId: Long, pageable: Pageable): Page<Ride>

    fun findAllByDriverId(driverId: Long, pageable: Pageable): Page<Ride>

    fun findByDriverIdOrderByCreatedAtDesc(driverId: Long, pageable: Pageable): Page<Ride>

    fun findAllByCustomerIdAndStatus(customerId: Long, status: RideStatus, pageable: Pageable): Page<Ride>

    fun findAllByCustomerIdAndStatusIn(customerId: Long, statuses: Collection<RideStatus>, pageable: Pageable): Page<Ride>

    fun findByCustomerIdAndStatusIn(customerId: Long, statuses: Collection<RideStatus>): List<Ride>

    fun findAllByDriverIdAndStatus(driverId: Long, status: RideStatus, pageable: Pageable): Page<Ride>

    fun findAllByDriverIdAndStatusIn(driverId: Long, statuses: Collection<RideStatus>, pageable: Pageable): Page<Ride>

    fun findAllByStatus(status: RideStatus, pageable: Pageable): Page<Ride>

    fun findAllByStatusIn(statuses: Collection<RideStatus>, pageable: Pageable): Page<Ride>

    // =========================================================================
    // 4. NHÓM HÀM CẬP NHẬT TRẠNG THÁI (Ngăn Race Condition)
    // =========================================================================

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
        update rides
        set driver_id = :driverId,
            accepted_at = CURRENT_TIMESTAMP,
            status = 'DRIVER_ASSIGNED',
            updated_at = CURRENT_TIMESTAMP,
            version = version + 1
        where id = :rideId
          and status = 'SEARCHING'
        """,
        nativeQuery = true,
    )
    fun assignDriverIfSearching(
        @Param("rideId") rideId: Long,
        @Param("driverId") driverId: Long,
    ): Int
}
