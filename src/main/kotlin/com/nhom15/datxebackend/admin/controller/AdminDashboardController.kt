package com.nhom15.datxebackend.admin.controller

import com.nhom15.datxebackend.admin.service.AdminDashboardService
import com.nhom15.datxebackend.common.api.ApiResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/dashboard")
@PreAuthorize("hasRole('ADMIN')")
class AdminDashboardController(private val service: AdminDashboardService) {
    @GetMapping
    fun summary(@RequestParam(defaultValue = "7") days: Int) = ApiResponse.ok(service.summary(days))
}
