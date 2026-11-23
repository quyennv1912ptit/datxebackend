package com.nhom15.datxebackend.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseToken
import com.nhom15.datxebackend.admin.repository.AdminRepository
import com.nhom15.datxebackend.auth.dto.AuthRegisterRequest
import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.auth.service.AuthService
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.notification.token.DeviceTokenService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Optional

class AuthTombstoneTests {
    private val token = Mockito.mock(FirebaseToken::class.java, Answer<Any?> { invocation ->
        if (invocation.method.name == "getUid") "deleted-uid" else null
    })
    private val firebaseAuth = Mockito.mock(FirebaseAuth::class.java, Answer<Any?> { invocation ->
        if (invocation.method.name == "verifyIdToken" &&
            invocation.arguments[0] == "id-token" && invocation.arguments[1] == true
        ) token else null
    })
    private val customers = Mockito.mock(CustomerRepository::class.java)
    private val drivers = Mockito.mock(DriverRepository::class.java)
    private val admins = Mockito.mock(AdminRepository::class.java)
    private val deviceTokens = Mockito.mock(DeviceTokenService::class.java)
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val service = AuthService(
        firebaseAuth, customers, drivers, admins, deviceTokens, AuthenticatedUserCache(3000L), jdbc,
    )

    @Test
    fun `a deleted driver uid cannot register a new driver account`() {
        Mockito.`when`(drivers.findByFirebaseUid("deleted-uid")).thenReturn(Optional.empty())
        Mockito.`when`(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM deleted_firebase_uids WHERE firebase_uid = ?)",
                Boolean::class.java, "deleted-uid",
            ),
        ).thenReturn(true)

        val error = assertThrows(AppException::class.java) {
            service.registerDriver(AuthRegisterRequest("id-token", "Tên tài xế", "+84901234567"))
        }

        assertEquals(ErrorCode.ACCOUNT_LOCKED, error.errorCode)
        Mockito.verifyNoInteractions(customers, admins, deviceTokens)
    }

    @Test
    fun `a live uid still follows the normal cross role guard`() {
        Mockito.`when`(drivers.findByFirebaseUid("deleted-uid")).thenReturn(Optional.empty())
        Mockito.`when`(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM deleted_firebase_uids WHERE firebase_uid = ?)",
                Boolean::class.java, "deleted-uid",
            ),
        ).thenReturn(false)
        Mockito.`when`(admins.existsByFirebaseUid("deleted-uid")).thenReturn(true)

        val error = assertThrows(AppException::class.java) {
            service.registerDriver(AuthRegisterRequest("id-token", "Tên tài xế", "+84901234567"))
        }

        assertEquals(ErrorCode.ACCOUNT_ALREADY_EXISTS, error.errorCode)
    }
}
