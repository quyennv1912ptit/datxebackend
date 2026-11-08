package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.*
import com.nhom15.datxebackend.common.enums.*
import com.nhom15.datxebackend.payment.exception.CreditWalletBelowMinimumException
import com.nhom15.datxebackend.payment.service.WalletService
import com.nhom15.datxebackend.tracking.service.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito.*
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.Optional

class DriverCreditAccessTests {
    @Test fun `insufficient credit blocks online before Redis registration`() {
        val drivers = mock(DriverRepository::class.java)
        val wallets = mock(WalletService::class.java)
        val locations = mock(LocationService::class.java)
        val redis = mock(StringRedisTemplate::class.java)
        val driver = Driver().apply { status = DriverStatus.OFFLINE }
        `when`(drivers.findByIdForUpdate(7)).thenReturn(Optional.of(driver))
        doThrow(CreditWalletBelowMinimumException()).`when`(wallets).requireEligibleDriver(7)
        val presence = DriverPresenceService(drivers, mock(DriverSkillRepository::class.java), locations, redis, wallets)
        assertThrows(CreditWalletBelowMinimumException::class.java) { presence.setOnline(7, true, VehicleType.CAR) }
        assertEquals(DriverStatus.OFFLINE, driver.status)
        verifyNoInteractions(redis, locations)
        verify(drivers, never()).save(driver)
    }
}
