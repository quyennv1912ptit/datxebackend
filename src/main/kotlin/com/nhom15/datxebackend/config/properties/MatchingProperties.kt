package com.nhom15.datxebackend.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.matching")
data class MatchingProperties(
    val searchRadiiKm: List<Double> = listOf(2.0, 4.0, 7.0, 10.0),
    val maxCandidatesPerRound: Int = 20,
    val offerTimeout: Duration = Duration.ofSeconds(15),
    val driverAliveTtl: Duration = Duration.ofSeconds(30),
) {
    init {
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
