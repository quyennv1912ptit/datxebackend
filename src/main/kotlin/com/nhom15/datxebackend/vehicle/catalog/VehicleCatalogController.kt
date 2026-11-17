package com.nhom15.datxebackend.vehicle.catalog

import com.nhom15.datxebackend.common.api.ApiResponse
import com.nhom15.datxebackend.driver.verification.LicenseDecision
import com.nhom15.datxebackend.driver.verification.LicenseEligibilityPolicy
import com.nhom15.datxebackend.driver.verification.LicenseEvidence
import com.nhom15.datxebackend.driver.verification.LicensePurpose
import com.nhom15.datxebackend.driver.verification.LicenseRestriction
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId

data class LicensePreviewRequest(
    @field:Size(max = 20)
    val licenseClasses: List<@Pattern(regexp = "[A-Z0-9]{1,8}") String>,
    val issuedOn: LocalDate? = null,
    val expiresOn: LocalDate? = null,
    val noExpiry: Boolean? = null,
    val restriction: LicenseRestriction = LicenseRestriction.UNKNOWN,
    @field:Size(max = 20)
    val restrictionCode: String? = null,
)

data class LicenseGroupPreview(val groupId: String, val label: String, val result: LicenseDecision)

@RestController
class VehicleCatalogController(private val policy: LicenseEligibilityPolicy) {
    @GetMapping("/api/vehicle-catalog")
    fun read() = ApiResponse.ok(
        mapOf("version" to VehicleCatalog.VERSION, "groups" to VehicleCatalog.groups,
            "bookingTypes" to VehicleCatalog.bookingTypes, "transmissions" to VehicleCatalog.transmissions,
            "models" to VehicleCatalog.models),
    )

    @PostMapping("/api/admin/vehicle-catalog/license-preview")
    @PreAuthorize("hasRole('ADMIN')")
    fun preview(@Valid @RequestBody request: LicensePreviewRequest): ApiResponse<List<LicenseGroupPreview>> {
        val evidence = LicenseEvidence(request.licenseClasses, request.issuedOn, request.expiresOn,
            request.noExpiry, request.restriction, request.restrictionCode)
        val today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"))
        return ApiResponse.ok(VehicleCatalog.groups.filter { it.active && it.supported }.map {
            LicenseGroupPreview(it.id, it.label, policy.evaluate(evidence, it, LicensePurpose.PAID_DRIVER, today))
        })
    }
}
