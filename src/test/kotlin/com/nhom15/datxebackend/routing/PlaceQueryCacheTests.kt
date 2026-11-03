package com.nhom15.datxebackend.routing

import com.nhom15.datxebackend.routing.service.PlaceQueryCache
import com.nhom15.datxebackend.routing.service.normalizeQuery
import com.nhom15.datxebackend.routing.service.quantize
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Cache dùng để giảm số lần gọi Goong — cần chắc chắn nó hết hạn và chuẩn hoá đúng. */
class PlaceQueryCacheTests {
    @Test
    fun `tra ve gia tri da cache trong thoi han`() {
        val cache = PlaceQueryCache<String>(Duration.ofMinutes(5))
        cache.put("ho-guom", "Hồ Gươm")
        assertEquals("Hồ Gươm", cache.get("ho-guom"))
        assertNull(cache.get("khac"))
    }

    @Test
    fun `het han thi khong tra ve nua`() {
        val cache = PlaceQueryCache<String>(Duration.ofMillis(1))
        cache.put("a", "b")
        Thread.sleep(5)
        assertNull(cache.get("a"))
    }

    @Test
    fun `chuan hoa chuoi tim kiem va toa do`() {
        assertEquals("hồ gươm", normalizeQuery("  Hồ   Gươm "))
        assertEquals(21.03, quantize(21.0285, 2))
        assertEquals(105.85, quantize(105.8522, 2))
    }
}
