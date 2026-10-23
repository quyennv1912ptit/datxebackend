package com.nhom15.datxebackend.pricing.controller

import com.nhom15.datxebackend.pricing.dto.CreatePricingRuleRequest
import com.nhom15.datxebackend.pricing.dto.UpdatePricingRuleRequest
import com.nhom15.datxebackend.pricing.dto.PricingRuleResponse
import com.nhom15.datxebackend.pricing.service.PricingRuleService
import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/pricing-rules")
@PreAuthorize("hasRole('ADMIN')")
class AdminPricingRuleController(
    private val pricingRuleService: PricingRuleService,
) {
    @GetMapping
    fun listRules(@CurrentUser actor: AuthUser): ApiResponse<List<PricingRuleResponse>> =
        ApiResponse.ok(pricingRuleService.listRules(actor))

    @PostMapping
    fun createRule(
        @CurrentUser actor: AuthUser,
        @Valid @RequestBody request: CreatePricingRuleRequest,
    ): ResponseEntity<ApiResponse<PricingRuleResponse>> = ResponseEntity
        .status(HttpStatus.CREATED)
        .body(ApiResponse.ok(pricingRuleService.createRule(actor, request)))

    @PutMapping("/{id}")
    fun updateRule(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdatePricingRuleRequest,
    ): ApiResponse<PricingRuleResponse> = ApiResponse.ok(pricingRuleService.updateRule(actor, id, request))

    @PostMapping("/{id}/activate")
    fun activateRule(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
    ): ApiResponse<PricingRuleResponse> = ApiResponse.ok(pricingRuleService.activateRule(actor, id))

    @PostMapping("/{id}/deactivate")
    fun deactivateRule(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @RequestParam(defaultValue = "false") force: Boolean,
    ): ApiResponse<PricingRuleResponse> = ApiResponse.ok(pricingRuleService.deactivateRule(actor, id, force))
}
