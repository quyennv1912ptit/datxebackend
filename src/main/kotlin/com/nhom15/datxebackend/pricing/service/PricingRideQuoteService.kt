package com.nhom15.datxebackend.pricing.service

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.exception.NotFoundException
import com.nhom15.datxebackend.pricing.model.RideQuote
import com.nhom15.datxebackend.pricing.model.RideQuoteInput
import com.nhom15.datxebackend.pricing.entity.PricingRule
import com.nhom15.datxebackend.pricing.repository.PricingRuleRepository
import com.nhom15.datxebackend.pricing.api.RideQuoteProvider
import com.nhom15.datxebackend.routing.api.DrivingRouteProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.springframework.stereotype.Service

@Service
class PricingRideQuoteService(
    private val pricingRuleRepository: PricingRuleRepository,
    private val drivingRouteProvider: DrivingRouteProvider,
) : RideQuoteProvider {

    override fun quote(input: RideQuoteInput): RideQuote {
        val rule = pricingRuleRepository.findFirstByVehicleTypeAndActiveTrueOrderByCreatedAtDesc(input.vehicleType)
            ?: throw NotFoundException(ErrorCode.PRICING_RULE_NOT_FOUND)

        val route = drivingRouteProvider.findRoute(input.pickup, input.dropoff, input.vehicleType)
        if (!route.distanceMeters.isFinite() || route.distanceMeters < 0.0 ||
            !route.durationSeconds.isFinite() || route.durationSeconds < 0.0
        ) {
            throw AppException(ErrorCode.ROUTE_UNAVAILABLE)
        }
        val distanceKm = BigDecimal.valueOf(route.distanceMeters)
            .divide(METERS_PER_KILOMETER)
            .setScale(DISTANCE_SCALE, RoundingMode.HALF_UP)
        val estimatedDurationMinutes = BigDecimal.valueOf(route.durationSeconds)
            .divide(SECONDS_PER_MINUTE, 0, RoundingMode.CEILING)
            .longValueExact()

        val distanceFare = distanceKm
            .multiply(BigDecimal.valueOf(rule.perKm))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
        val surchargeBase = Math.addExact(rule.baseFare, distanceFare)
        val nightSurcharge = if (isNight(input.pricingAt, rule)) {
            BigDecimal.valueOf(surchargeBase)
                .multiply(BigDecimal.valueOf(rule.nightSurchargePercent.toLong()))
                .divide(ONE_HUNDRED)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        } else {
            0L
        }

        val rawFare = Math.addExact(
            Math.addExact(surchargeBase, nightSurcharge),
            rule.returnFeeForDriver,
        )
        return RideQuote(
            pricingRuleId = rule.id,
            distanceKm = distanceKm,
            estimatedDurationMinutes = estimatedDurationMinutes,
            baseFare = rule.baseFare,
            distanceFare = distanceFare,
            // Chưa có dữ liệu phút chờ; duration tuyến đường chỉ dùng để ước tính thời gian lái xe.
            waitingFare = 0L,
            nightSurcharge = nightSurcharge,
            returnFee = rule.returnFeeForDriver,
            estimatedFare = maxOf(rawFare, rule.minFare),
        )
    }

    private fun isNight(at: Instant, rule: PricingRule): Boolean {
        if (rule.nightSurchargePercent == 0) return false
        val start = rule.nightStart ?: return false
        val end = rule.nightEnd ?: return false
        if (start == end) return false

        val time = at.atZone(VIETNAM_ZONE).toLocalTime()
        return if (start < end) {
            time.inHalfOpenRange(start, end)
        } else {
            !time.isBefore(start) || time.isBefore(end)
        }
    }

    private fun LocalTime.inHalfOpenRange(start: LocalTime, end: LocalTime): Boolean =
        !isBefore(start) && isBefore(end)

    private companion object {
        const val DISTANCE_SCALE = 3
        val ONE_HUNDRED: BigDecimal = BigDecimal.valueOf(100)
        val METERS_PER_KILOMETER: BigDecimal = BigDecimal.valueOf(1_000)
        val SECONDS_PER_MINUTE: BigDecimal = BigDecimal.valueOf(60)
        val VIETNAM_ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
    }
}
