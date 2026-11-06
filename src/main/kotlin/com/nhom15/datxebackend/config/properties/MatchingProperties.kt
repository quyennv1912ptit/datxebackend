package com.nhom15.datxebackend.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

enum class DriverRanking { DISTANCE, SCORE }

@ConfigurationProperties("app.matching")
data class MatchingProperties(
    val searchRadiiKm: List<Double> = listOf(2.0, 3.0, 5.0),
    val maxCandidatesPerRound: Int = 5,
    val candidateScanLimit: Int = 500,
    val ignoredThreshold: Int = 3,
    val ignoredPenalty: Double = 20.0,
    val ignoredWindow: Duration = Duration.ofMinutes(30),
    val rankingMode: DriverRanking = DriverRanking.DISTANCE,
    val offerTimeout: Duration = Duration.ofSeconds(12),
    val searchTimeout: Duration = Duration.ofSeconds(90),
    val rescanDelay: Duration = Duration.ofSeconds(3),
    val offerGrace: Duration = Duration.ofSeconds(1),
    val clientOfferGrace: Duration = Duration.ofSeconds(2),
    val maxRedispatch: Int = 2,
    val rejectedTtl: Duration = Duration.ofSeconds(120),
    val idempotencyTtl: Duration = Duration.ofMinutes(5),
    val maxTickFailures: Int = 3,
    val driverDisconnectTimeout: Duration = Duration.ofSeconds(60),
    val tickLockTtl: Duration = Duration.ofSeconds(5),
    val driverAliveTtl: Duration = Duration.ofSeconds(30),
) {
    init {
        require(rejectedTtl >= searchTimeout && !idempotencyTtl.isNegative && !idempotencyTtl.isZero)
        require(candidateScanLimit >= maxCandidatesPerRound && ignoredThreshold > 0 && ignoredPenalty >= 0)
        require(!ignoredWindow.isNegative && !ignoredWindow.isZero)
        require(searchTimeout > offerTimeout && !rescanDelay.isNegative && !rescanDelay.isZero)
        require(!offerGrace.isNegative && !clientOfferGrace.isNegative && clientOfferGrace < offerTimeout)
        require(maxTickFailures > 0)
        require(maxRedispatch >= 0 && driverDisconnectTimeout > driverAliveTtl)
        require(!tickLockTtl.isNegative && !tickLockTtl.isZero)
        require(searchRadiiKm.isNotEmpty()) { "app.matching.search-radii-km không được để trống" }
        require(searchRadiiKm.all { it.isFinite() && it > 0.0 }) {
            "app.matching.search-radii-km phải chứa các bán kính hữu hạn và lớn hơn 0"
        }
        require(searchRadiiKm.zipWithNext().all { (current, next) -> current < next }) {
            "app.matching.search-radii-km phải tăng dần"
        }
        require(maxCandidatesPerRound > 0) { "app.matching.max-candidates-per-round phải lớn hơn 0" }
        require(!offerTimeout.isNegative && !offerTimeout.isZero) { "app.matching.offer-timeout phải lớn hơn 0" }
        require(!driverAliveTtl.isNegative && !driverAliveTtl.isZero) { "app.matching.driver-alive-ttl phải lớn hơn 0" }
    }
}
