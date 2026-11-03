package com.nhom15.datxebackend.routing.service

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache TTL nhỏ trong bộ nhớ để giảm số lần gọi Goong: nhiều người tìm cùng một
 * địa chỉ, hoặc người dùng gõ đi gõ lại, sẽ dùng lại kết quả. Chỉ cache kết quả
 * thành công, tự hết hạn và giới hạn số entry để không phình RAM.
 */
class PlaceQueryCache<V>(private val ttl: Duration, private val maxEntries: Int = 1_000) {
    private class Entry<V>(val value: V, val expiresAt: Long)

    private val store = ConcurrentHashMap<String, Entry<V>>()

    fun get(key: String): V? {
        val entry = store[key] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            store.remove(key, entry)
            return null
        }
        return entry.value
    }

    fun put(key: String, value: V) {
        if (store.size >= maxEntries) store.clear()
        store[key] = Entry(value, System.currentTimeMillis() + ttl.toMillis())
    }
}

/** Chuẩn hoá chuỗi tìm kiếm để "Hồ Gươm", "hồ  gươm" dùng chung một cache key. */
internal fun normalizeQuery(query: String): String =
    query.trim().lowercase().replace(Regex("\\s+"), " ")

/** Làm tròn toạ độ (~1km cho gợi ý, ~1m cho geocode ngược) để cache dùng lại được. */
internal fun quantize(value: Double, decimals: Int): Double {
    val factor = when (decimals) {
        1 -> 10.0
        2 -> 100.0
        3 -> 1_000.0
        4 -> 10_000.0
        else -> 100_000.0
    }
    return Math.round(value * factor) / factor
}
