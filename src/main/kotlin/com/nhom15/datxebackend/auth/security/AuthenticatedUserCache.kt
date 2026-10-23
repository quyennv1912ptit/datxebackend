package com.nhom15.datxebackend.auth.security

import com.nhom15.datxebackend.common.security.AuthUser
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** Short lived positive cache for request authentication; status changes evict entries after commit. */
@Component
class AuthenticatedUserCache(
    @Value("\${app.auth.user-cache-ttl-ms:3000}") private val ttlMillis: Long,
) {
    private data class Entry(val user: AuthUser, val expiresAtMillis: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(firebaseUid: String, loader: () -> AuthUser?): AuthUser? {
        val now = System.currentTimeMillis()
        entries[firebaseUid]?.let { cached ->
            if (cached.expiresAtMillis > now) return cached.user
            entries.remove(firebaseUid, cached)
        }

        val loaded = loader() ?: return null
        if (ttlMillis > 0) entries[firebaseUid] = Entry(loaded, now + ttlMillis)
        return loaded
    }

    fun evict(firebaseUid: String) {
        entries.remove(firebaseUid)
    }
}
