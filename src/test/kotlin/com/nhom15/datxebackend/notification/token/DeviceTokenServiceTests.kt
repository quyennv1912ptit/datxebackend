package com.nhom15.datxebackend.notification.token

import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.ForbiddenException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/** Registering a device token must never rebind a row owned by somebody else. */
class DeviceTokenServiceTests {
    private val repository = Mockito.mock(DeviceTokenRepository::class.java)
    private val service = DeviceTokenService(repository)

    @Test
    fun `attacker cannot rebind a victim token onto their own installation`() {
        val attackerInstallation = token("attacker-install", Role.CUSTOMER, 99L, "attacker-token")
        val victimToken = token("victim-install", Role.CUSTOMER, 10L, "victim-token")
        Mockito.`when`(repository.findByInstallationId("attacker-install")).thenReturn(attackerInstallation)
        Mockito.`when`(repository.findByFcmToken("victim-token")).thenReturn(victimToken)

        val error = assertThrows(ForbiddenException::class.java) {
            service.registerOrUpdate(99L, Role.CUSTOMER, "attacker-install", "victim-token", "ANDROID")
        }

        assertEquals(ErrorCode.FORBIDDEN, error.errorCode)
        // The victim's row must not be deleted to resolve the token collision either.
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())
        Mockito.verify(repository, Mockito.never()).delete(Mockito.any())
    }

    @Test
    fun `attacker cannot take over a victim installation even with the same numeric id`() {
        val victimInstallation = token("victim-install", Role.CUSTOMER, 10L, "victim-token")
        val attackerToken = token("attacker-install", Role.DRIVER, 10L, "driver-token")
        Mockito.`when`(repository.findByInstallationId("victim-install")).thenReturn(victimInstallation)
        Mockito.`when`(repository.findByFcmToken("driver-token")).thenReturn(attackerToken)

        assertThrows(ForbiddenException::class.java) {
            service.registerOrUpdate(10L, Role.DRIVER, "victim-install", "driver-token", "ANDROID")
        }

        assertEquals(10L, victimInstallation.ownerId)
        assertEquals(Role.CUSTOMER, victimInstallation.role)
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())
        Mockito.verify(repository, Mockito.never()).delete(Mockito.any())
    }

    @Test
    fun `owner can refresh the token on their own installation`() {
        val own = token("my-install", Role.CUSTOMER, 10L, "old-token")
        Mockito.`when`(repository.findByInstallationId("my-install")).thenReturn(own)
        Mockito.`when`(repository.findByFcmToken("new-token")).thenReturn(null)

        service.registerOrUpdate(10L, Role.CUSTOMER, "my-install", "new-token", "IOS")

        assertEquals("new-token", own.fcmToken)
        assertEquals("IOS", own.platform)
        Mockito.verify(repository).save(own)
        Mockito.verify(repository, Mockito.never()).delete(Mockito.any())
    }

    @Test
    fun `unregister only deletes a token registered for the caller`() {
        service.unregister(99L, Role.CUSTOMER, "victim-install")
        Mockito.verify(repository)
            .findByInstallationIdAndOwnerIdAndRole("victim-install", 99L, Role.CUSTOMER)
        Mockito.verify(repository, Mockito.never()).delete(Mockito.any())

        val victimToken = token("victim-install", Role.CUSTOMER, 10L, "victim-token")
        Mockito.`when`(repository.findByInstallationIdAndOwnerIdAndRole("victim-install", 10L, Role.CUSTOMER))
            .thenReturn(victimToken)

        service.unregister(10L, Role.CUSTOMER, "victim-install")

        Mockito.verify(repository).delete(victimToken)
    }

    private fun token(
        installationId: String,
        role: Role,
        ownerId: Long,
        fcmToken: String
    ) = DeviceToken(
        installationId = installationId,
        role = role,
        ownerId = ownerId,
        fcmToken = fcmToken,
        platform = "ANDROID",
    )
}
