package com.nhom15.datxebackend.admin

import com.nhom15.datxebackend.admin.controller.AdminRideController
import com.nhom15.datxebackend.admin.service.AdminRideService
import com.nhom15.datxebackend.chat.service.ChatService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class AdminRideCapabilitiesHttpTests {
    @Test
    fun `capabilities route is not interpreted as a numeric ride id`() {
        val service = Mockito.mock(AdminRideService::class.java)
        val controller = AdminRideController(service, Mockito.mock(ChatService::class.java))
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(JacksonJsonHttpMessageConverter(mapper))
            .build()

        mvc.perform(get("/api/admin/rides/filter-capabilities"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.search").value(true))
            .andExpect(jsonPath("$.data.createdRange").value(true))
        Mockito.verifyNoInteractions(service)
    }

    @Test
    fun `ride list binds advanced filters and UTC dates at the HTTP boundary`() {
        val service = Mockito.mock(AdminRideService::class.java)
        val actor = com.nhom15.datxebackend.common.security.AuthUser(1,"admin",com.nhom15.datxebackend.common.enums.Role.ADMIN,"Admin",null,null,"ACTIVE")
        val expected = com.nhom15.datxebackend.ride.dto.request.RideListFilter(
            status="COMPLETED,IN_PROGRESS", page=2, size=50, q="Khách Bình",
            createdFrom=java.time.Instant.parse("2026-11-07T17:00:00Z"), createdTo=java.time.Instant.parse("2026-11-08T17:00:00Z"),
        )
        Mockito.`when`(service.listRides(actor,expected)).thenReturn(com.nhom15.datxebackend.common.api.PageResponse(emptyList(),2,50,0,0,false))
        val controller = AdminRideController(service,Mockito.mock(ChatService::class.java))
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        val mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
            .setMessageConverters(JacksonJsonHttpMessageConverter(mapper)).build()
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,null)
        try {
            mvc.perform(get("/api/admin/rides").param("status","COMPLETED,IN_PROGRESS")
                .param("page","2").param("size","50").param("q","Khách Bình")
                .param("createdFrom","2026-11-07T17:00:00.000Z").param("createdTo","2026-11-08T17:00:00.000Z"))
                .andExpect(status().isOk)
            Mockito.verify(service).listRides(actor,expected)
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext() }
    }
}
