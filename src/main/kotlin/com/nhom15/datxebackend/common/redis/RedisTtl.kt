package com.nhom15.datxebackend.common.redis

import java.time.Duration

object RedisTtl {
    val OFFER_TIMEOUT: Duration = Duration.ofSeconds(15)

    val DRIVER_LOCK: Duration = OFFER_TIMEOUT.plusSeconds(5)

    val DRIVER_ALIVE: Duration = Duration.ofSeconds(30)
    val DRIVER_LOCATION: Duration = Duration.ofSeconds(60)
    val DRIVER_STATS: Duration = Duration.ofDays(1)

    val RIDE_STATE: Duration = Duration.ofHours(24)
    val RIDE_CANDIDATES: Duration = Duration.ofHours(1)
    val RIDE_AFTER_END: Duration = Duration.ofHours(1)

    val IDEMPOTENCY: Duration = Duration.ofHours(24)
}