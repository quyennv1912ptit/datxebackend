package com.nhom15.datxebackend.ride

import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.exception.GlobalExceptionHandler
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.ride.controller.RideController
import com.nhom15.datxebackend.ride.dto.request.CancelRideRequest
import com.nhom15.datxebackend.ride.dto.response.RideStatusResponse
import com.nhom15.datxebackend.ride.service.RideEstimateService
import com.nhom15.datxebackend.ride.service.RideQueryService
import com.nhom15.datxebackend.ride.service.RideService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

class RideCancellationHttpTests {
    @Test
    fun `customer cancellation accepts an empty JSON object at the HTTP boundary`() {
        val customer = AuthUser(11, "customer", Role.CUSTOMER, "Khách", null, null, "ACTIVE")
        val service = Mockito.mock(RideService::class.java)
        val request = CancelRideRequest()
        Mockito.`when`(service.cancelRide(customer, 42L, request)).thenReturn(
            RideStatusResponse(42L, RideStatus.SEARCHING, RideStatus.CANCELLED, Instant.EPOCH),
        )
        val controller = RideController(
            service,
            Mockito.mock(RideQueryService::class.java),
            Mockito.mock(RideEstimateService::class.java),
        )
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .setMessageConverters(JacksonJsonHttpMessageConverter(mapper))
            .build()
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(customer, null)
        try {
            mvc.perform(post("/api/customer/rides/42/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.currentStatus").value("CANCELLED"))
            Mockito.verify(service).cancelRide(customer, 42L, request)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
