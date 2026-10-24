package com.nhom15.datxebackend.auth.security

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** Short lived positive cache for request authentication; status changes evict entries after commit. */
@Component
class AuthenticatedUserCache(
    @Value("\${app.auth.user-cache-ttl-ms:3000}") private val ttlMillis: Long,
) {
    private data class CacheKey(val firebaseUid: String, val role: Role)
    private data class Entry(val user: AuthUser, val expiresAtMillis: Long)

    private val entries = ConcurrentHashMap<CacheKey, Entry>()

    fun get(firebaseUid: String, role: Role, loader: () -> AuthUser?): AuthUser? {
        val key = CacheKey(firebaseUid, role)
        val now = System.currentTimeMillis()
        entries[key]?.let { cached ->
            if (cached.expiresAtMillis > now) return cached.user
            entries.remove(key, cached)
        }

        val loaded = loader() ?: return null
        if (ttlMillis > 0) entries[key] = Entry(loaded, now + ttlMillis)
        return loaded
    }

    fun evict(firebaseUid: String) {
        entries.keys.removeIf { it.firebaseUid == firebaseUid }
    }
}
