package com.nhom15.datxebackend.auth.controller

import com.nhom15.datxebackend.auth.dto.AuthLoginRequest
import com.nhom15.datxebackend.auth.dto.AuthRegisterRequest
import com.nhom15.datxebackend.auth.dto.AuthLogoutRequest
import com.nhom15.datxebackend.auth.dto.AuthProfileResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.auth.service.AuthService
import com.nhom15.datxebackend.auth.service.AccountDeletionService
import com.nhom15.datxebackend.common.api.ApiResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.servlet.http.HttpServletRequest
import com.nhom15.datxebackend.auth.service.AuthLoginRateLimiter

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val accountDeletionService: AccountDeletionService,
    private val loginRateLimiter: AuthLoginRateLimiter,
) {
    @PostMapping("/customer/login")
    fun customerLogin(
        @Valid @RequestBody request: AuthLoginRequest,
        servletRequest: HttpServletRequest,
    ): ApiResponse<AuthProfileResponse> {
        loginRateLimiter.check(servletRequest.remoteAddr)
        return ApiResponse.ok(authService.loginCustomer(request))
    }

    @PostMapping("/customer/register")
    fun customerRegister(
        @Valid @RequestBody request: AuthRegisterRequest,
        servletRequest: HttpServletRequest,
    ): ApiResponse<AuthProfileResponse> {
        loginRateLimiter.check(servletRequest.remoteAddr)
        return ApiResponse.ok(authService.registerCustomer(request))
    }

    @PostMapping("/driver/login")
    fun driverLogin(
        @Valid @RequestBody request: AuthLoginRequest,
        servletRequest: HttpServletRequest,
    ): ApiResponse<AuthProfileResponse> {
        loginRateLimiter.check(servletRequest.remoteAddr)
        return ApiResponse.ok(authService.loginDriver(request))
    }

    @PostMapping("/driver/register")
    fun driverRegister(
        @Valid @RequestBody request: AuthRegisterRequest,
        servletRequest: HttpServletRequest,
    ): ApiResponse<AuthProfileResponse> {
        loginRateLimiter.check(servletRequest.remoteAddr)
        return ApiResponse.ok(authService.registerDriver(request))
    }

    @PostMapping("/admin/login")
    fun adminLogin(
        @Valid @RequestBody request: AuthLoginRequest,
        servletRequest: HttpServletRequest,
    ): ApiResponse<AuthProfileResponse> {
        loginRateLimiter.check(servletRequest.remoteAddr)
        return ApiResponse.ok(authService.loginAdmin(request))
    }

    @GetMapping("/me")
    fun me(@CurrentUser user: AuthUser): ApiResponse<AuthProfileResponse> =
        ApiResponse.ok(authService.profileOf(user))

    @PostMapping("/logout")
    fun logout(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: AuthLogoutRequest,
    ): ApiResponse<String> {
        authService.logout(user, request.installationId)
        return ApiResponse.ok("Đăng xuất thiết bị thành công")
    }

    @DeleteMapping("/account")
    fun deleteAccount(@CurrentUser user: AuthUser): ApiResponse<String> {
        accountDeletionService.deleteAccount(user)
        return ApiResponse.ok("Tài khoản đã được xóa")
    }
}
