package com.nhom15.datxebackend.tracking
import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.tracking.dto.DriverLocation
import com.nhom15.datxebackend.tracking.dto.DriverLocationPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
class DriverLocationPolicyTests {
    @Test fun `GPS requires sane coordinates accuracy and sample age`() {
        val now = Instant.parse("2026-10-28T04:00:00Z")
        val valid = DriverLocation(GeoPoint(21.03, 105.84), now, 12.0)
        assertTrue(DriverLocationPolicy.valid(valid, now))
        assertFalse(DriverLocationPolicy.valid(valid.copy(accuracy = -1.0), now))
        assertFalse(DriverLocationPolicy.valid(valid.copy(timestamp = now.plusSeconds(31)), now))
        assertFalse(DriverLocationPolicy.valid(valid.copy(timestamp = now.minusSeconds(121)), now))
        assertThrows(IllegalArgumentException::class.java) { GeoPoint(Double.NaN, 105.84) }
    }
}
