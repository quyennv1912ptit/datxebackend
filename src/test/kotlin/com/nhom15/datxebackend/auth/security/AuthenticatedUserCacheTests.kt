package com.nhom15.datxebackend.auth.security

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class AuthenticatedUserCacheTests {
    @Test
    fun `assignment and ride release expose new driver status only after commit`() {
        val cache = AuthenticatedUserCache(60_000)
        val driver = AuthUser(1, "driver", Role.DRIVER, null, null, null, "ONLINE")
        fun read(status: String) = cache.get(driver.firebaseUid, driver.role) { driver.copy(status = status) }?.status

        assertEquals("ONLINE", read("ONLINE"))
        for ((before, after) in listOf("ONLINE" to "BUSY", "BUSY" to "ONLINE", "ONLINE" to "OFFLINE")) {
            TransactionSynchronizationManager.initSynchronization()
            try {
                cache.evictAfterCommit(driver.firebaseUid)
                assertEquals(before, read(after))
                val synchronization = TransactionSynchronizationManager.getSynchronizations().single()
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
                assertEquals(before, read(after))
                synchronization.afterCommit()
                assertEquals(after, read(after))
            } finally {
                TransactionSynchronizationManager.clearSynchronization()
            }
        }
    }
}
