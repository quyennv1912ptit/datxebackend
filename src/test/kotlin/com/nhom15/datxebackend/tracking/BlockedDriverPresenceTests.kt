package com.nhom15.datxebackend.tracking

import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.ForbiddenException
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility
import com.nhom15.datxebackend.payment.service.WalletService
import com.nhom15.datxebackend.tracking.service.DriverPresenceService
import com.nhom15.datxebackend.tracking.service.LocationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.Optional

class BlockedDriverPresenceTests {
    @Test
    fun `blocked driver gets ACCOUNT_LOCKED and is removed from Redis before the exception`() {
        val drivers = Mockito.mock(DriverRepository::class.java)
        val locations = Mockito.mock(LocationService::class.java)
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        val blocked = Driver().apply {
            id = 7L
            firebaseUid = "drv-7"
            status = DriverStatus.BLOCKED
        }
        Mockito.`when`(drivers.findByIdForUpdate(7L)).thenReturn(Optional.of(blocked))

        val service = DriverPresenceService(
            drivers,
            locations,
            redis,
            Mockito.mock(WalletService::class.java),
            Mockito.mock(DriverVerificationEligibility::class.java),
            Mockito.mock(AuthenticatedUserCache::class.java),
        )

        val error = assertThrows(ForbiddenException::class.java) {
            service.updateAvailability(7L, true, VehicleType.CAR)
        }
        assertEquals(ErrorCode.ACCOUNT_LOCKED, error.errorCode)
        assertEquals(DriverStatus.BLOCKED, blocked.status)
        Mockito.verify(locations).removeDriver(7L)
    }
}
