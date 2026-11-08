package com.nhom15.datxebackend.common.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MoneyRoundingTests {
    @Test fun `rounds to nearest thousand with half up`() {
        assertEquals(31_000L, 31_499L.roundToNearestThousandVnd())
        assertEquals(32_000L, 31_500L.roundToNearestThousandVnd())
        assertEquals(31_000L, 31_250L.roundToNearestThousandVnd())
        assertEquals(32_000L, 31_750L.roundToNearestThousandVnd())
        assertEquals(0L, 0L.roundToNearestThousandVnd())
        assertEquals(1_000L, 999L.roundToNearestThousandVnd())
        assertEquals(1_000L, 1_000L.roundToNearestThousandVnd())
        assertEquals(45_000L, 45_000L.roundToNearestThousandVnd())
    }

    @Test fun `negative money is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { (-1L).roundToNearestThousandVnd() }
    }
}
