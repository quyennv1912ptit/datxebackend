package com.nhom15.datxebackend.admin.controller

import com.nhom15.datxebackend.admin.service.AdminRatingService
import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.common.api.PageResponse
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.CurrentUser
import com.nhom15.datxebackend.rating.dto.AdminRatingItem
import com.nhom15.datxebackend.rating.dto.RatingVisibilityRequest
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/admin/ratings")
@PreAuthorize("hasRole('ADMIN')")
class AdminRatingController(
    private val ratingService: AdminRatingService,
) {
    @GetMapping
    fun list(
        @CurrentUser actor: AuthUser,
        @RequestParam(required = false) direction: String?,
        @RequestParam(required = false) stars: Int?,
        @RequestParam(required = false) raterId: Long?,
        @RequestParam(required = false) rateeId: Long?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        from: LocalDate?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        to: LocalDate?,
        @RequestParam(required = false) hidden: Boolean?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<PageResponse<AdminRatingItem>> =
        ApiResponse.ok(
            ratingService.list(
                actor, direction, stars, raterId, rateeId,
                from, to, hidden, page, size,
            ),
        )

    @GetMapping("/{id}")
    fun detail(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
    ): ApiResponse<AdminRatingItem> =
        ApiResponse.ok(ratingService.detail(actor, id))

    @PutMapping("/{id}/visibility")
    fun updateVisibility(
        @CurrentUser actor: AuthUser,
        @PathVariable id: Long,
        @Valid @RequestBody request: RatingVisibilityRequest,
    ): ApiResponse<AdminRatingItem> =
        ApiResponse.ok(ratingService.updateVisibility(actor, id, request))
}