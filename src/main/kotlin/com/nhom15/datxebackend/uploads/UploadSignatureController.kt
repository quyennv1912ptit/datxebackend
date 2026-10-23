package com.nhom15.datxebackend.uploads

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/uploads")
class UploadSignatureController(private val signatureService: UploadSignatureService) {
    @GetMapping("/signature")
    fun getSignature(
        @CurrentUser actor: AuthUser,
        @RequestParam(required = false) purpose: String?,
        @RequestParam(required = false) rideId: Long?,
    ): ApiResponse<UploadSignatureResponse> = ApiResponse.ok(signatureService.create(actor, purpose, rideId))
}
