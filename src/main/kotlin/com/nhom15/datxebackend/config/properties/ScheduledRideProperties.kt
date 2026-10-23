package com.nhom15.datxebackend.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app.scheduled-ride")
data class ScheduledRideProperties(
    val dispatchLeadTime: Duration = Duration.ofMinutes(15),
    val expireAfter: Duration = Duration.ofMinutes(30)
) {
    init {
        require(!dispatchLeadTime.isNegative) {
            "app.scheduled-ride.dispatch-lead-time không được âm"
        }
        require(!expireAfter.isNegative && !expireAfter.isZero) {
            "app.scheduled-ride.expire-after phải lớn hơn 0"
        }
    }
}
