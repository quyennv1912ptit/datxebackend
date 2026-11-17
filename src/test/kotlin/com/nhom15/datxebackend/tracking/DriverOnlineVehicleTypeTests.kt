package com.nhom15.datxebackend.tracking

import com.google.firebase.auth.FirebaseAuth
import com.nhom15.datxebackend.admin.repository.AdminRepository
import com.nhom15.datxebackend.auth.controller.AuthController
import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.auth.service.AccountDeletionService
import com.nhom15.datxebackend.auth.service.AuthLoginRateLimiter
import com.nhom15.datxebackend.auth.service.AuthService
import com.nhom15.datxebackend.common.enums.DriverStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.common.exception.GlobalExceptionHandler
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.driver.repository.DriverSkillRepository
import com.nhom15.datxebackend.driver.verification.DriverVerificationEligibility
import com.nhom15.datxebackend.notification.token.DeviceTokenService
import com.nhom15.datxebackend.payment.service.WalletService
import com.nhom15.datxebackend.tracking.controller.DriverPresenceController
import com.nhom15.datxebackend.tracking.service.DriverPresenceService
import com.nhom15.datxebackend.tracking.service.LocationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.util.Optional

class DriverOnlineVehicleTypeTests {
    @Test
    fun `findAuthenticatedUser and profileOf expose onlineVehicleType only for ONLINE or BUSY drivers`() {
        val drivers = Mockito.mock(DriverRepository::class.java)
        val customers = Mockito.mock(CustomerRepository::class.java)
        val admins = Mockito.mock(AdminRepository::class.java)
        val cache = AuthenticatedUserCache(3000L)
        val authService = AuthService(
            Mockito.mock(FirebaseAuth::class.java),
            customers,
            drivers,
            admins,
            Mockito.mock(DeviceTokenService::class.java),
            cache,
        )

        val driver = Driver().apply {
            id = 44L
            firebaseUid = "drv-44"
            fullName = "Nguyễn Tài Xế"
            phoneNumber = "+84901234567"
            status = DriverStatus.ONLINE
            onlineVehicleType = VehicleType.CAR
            verificationStatus = "APPROVED"
        }
        Mockito.`when`(admins.findByFirebaseUid("drv-44")).thenReturn(Optional.empty())
        Mockito.`when`(customers.findByFirebaseUid("drv-44")).thenReturn(Optional.empty())
        Mockito.`when`(drivers.findByFirebaseUid("drv-44")).thenReturn(Optional.of(driver))

        val onlineUser = authService.findAuthenticatedUser("drv-44", Role.DRIVER)!!
        assertEquals(VehicleType.CAR, onlineUser.onlineVehicleType)
        assertEquals(VehicleType.CAR, authService.profileOf(onlineUser).onlineVehicleType)

        cache.evict("drv-44")
        driver.status = DriverStatus.BUSY
        driver.onlineVehicleType = VehicleType.MOTORCYCLE
        val busyUser = authService.findAuthenticatedUser("drv-44", Role.DRIVER)!!
        assertEquals(VehicleType.MOTORCYCLE, authService.profileOf(busyUser).onlineVehicleType)

        cache.evict("drv-44")
        driver.status = DriverStatus.OFFLINE
        driver.onlineVehicleType = VehicleType.MOTORCYCLE
        val offlineUser = authService.findAuthenticatedUser("drv-44", Role.DRIVER)!!
        assertNull(authService.profileOf(offlineUser).onlineVehicleType)
    }

    @Test
    fun `updateAvailability sets, clears and returns onlineVehicleType while evicting user cache`() {
        val drivers = Mockito.mock(DriverRepository::class.java)
        val skills = Mockito.mock(DriverSkillRepository::class.java)
        val locations = Mockito.mock(LocationService::class.java)
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        val wallets = Mockito.mock(WalletService::class.java)
        val cache = Mockito.mock(AuthenticatedUserCache::class.java)
        val eligibility = DriverVerificationEligibility()

        val driver = Driver().apply {
            id = 7L
            firebaseUid = "drv-7"
            status = DriverStatus.OFFLINE
            verificationStatus = "APPROVED"
        }
        Mockito.`when`(drivers.findByIdForUpdate(7L)).thenReturn(Optional.of(driver))
        Mockito.`when`(skills.hasVerifiedSkill(7L, VehicleType.CAR)).thenReturn(true)

        val service = DriverPresenceService(drivers, skills, locations, redis, wallets, eligibility, cache)

        val onlineResponse = service.updateAvailability(7L, true, VehicleType.CAR)
        assertEquals(DriverStatus.ONLINE, onlineResponse.status)
        assertEquals(VehicleType.CAR, onlineResponse.onlineVehicleType)
        assertEquals(VehicleType.CAR, driver.onlineVehicleType)
        Mockito.verify(cache).evict("drv-7")

        val offlineResponse = service.updateAvailability(7L, false, null)
        assertEquals(DriverStatus.OFFLINE, offlineResponse.status)
        assertNull(offlineResponse.onlineVehicleType)
        assertNull(driver.onlineVehicleType)
        Mockito.verify(cache, Mockito.times(2)).evict("drv-7")
    }

    @Test
    fun `auth me and driver availability endpoints serialize onlineVehicleType at HTTP boundary`() {
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val authService = Mockito.mock(AuthService::class.java)
        val presenceService = Mockito.mock(DriverPresenceService::class.java)

        val driverUser = AuthUser(
            id = 9L,
            firebaseUid = "drv-9",
            role = Role.DRIVER,
            fullName = "Lê Văn B",
            email = null,
            phoneNumber = "+84909999999",
            status = "ONLINE",
            verificationStatus = "APPROVED",
            onlineVehicleType = VehicleType.MOTORCYCLE,
        )
        Mockito.`when`(authService.profileOf(driverUser)).thenReturn(
            com.nhom15.datxebackend.auth.dto.AuthProfileResponse(
                id = 9L,
                role = Role.DRIVER,
                firebaseUid = "drv-9",
                fullName = "Lê Văn B",
                email = null,
                phoneNumber = "+84909999999",
                status = "ONLINE",
                verificationStatus = "APPROVED",
                onlineVehicleType = VehicleType.MOTORCYCLE,
            ),
        )
        Mockito.`when`(presenceService.updateAvailability(9L, true, VehicleType.CAR)).thenReturn(
            com.nhom15.datxebackend.tracking.dto.DriverAvailabilityResponse(DriverStatus.ONLINE, VehicleType.CAR),
        )

        val authController = AuthController(
            authService,
            Mockito.mock(AccountDeletionService::class.java),
            Mockito.mock(AuthLoginRateLimiter::class.java),
        )
        val presenceController = DriverPresenceController(presenceService)
        val mvc = MockMvcBuilders.standaloneSetup(authController, presenceController)
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .setMessageConverters(JacksonJsonHttpMessageConverter(mapper))
            .build()

        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(driverUser, null)
        try {
            mvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.status").value("ONLINE"))
                .andExpect(jsonPath("$.data.onlineVehicleType").value("MOTORCYCLE"))

            mvc.perform(
                put("/api/driver/availability")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"online":true,"vehicle_type":"CAR"}"""),
            ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.status").value("ONLINE"))
                .andExpect(jsonPath("$.data.onlineVehicleType").value("CAR"))
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
