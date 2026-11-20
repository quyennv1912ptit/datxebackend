package com.nhom15.datxebackend.driver.verification

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFails

class VerificationDecisionRequestTests {
    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .findAndAddModules()
        .build()

    private val approval = """
        {
          "revision": 6,
          "status": "APPROVED",
          "capabilityReview": {
            "policyVersion": "VN_36_2024_108_2026_MVP_1",
            "issuedOn": "2025-10-30",
            "restriction": "VERIFIED_NONE",
            "restrictionCode": null,
            "selectedGroupIds": ["moto-50-125"]
          },
          "captures": [],
          "fields": []
        }
    """.trimIndent()

    @Test
    fun `admin approval JSON accepts server managed submission revision`() {
        val request = mapper.readValue(approval, VerificationDecisionRequest::class.java)
        assertEquals("APPROVED", request.status)
        val review = requireNotNull(request.capabilityReview)
        assertEquals(0L, review.submissionRevision)
        assertEquals(LocalDate.of(2025, 10, 30), review.issuedOn)
        assertEquals(LicenseRestriction.VERIFIED_NONE, review.restriction)
        assertEquals(listOf("moto-50-125"), review.selectedGroupIds)
    }

    @Test
    fun `approval still rejects malformed license evidence`() {
        for (invalid in listOf(
            approval.replace("2025-10-30", "not-a-date"),
            approval.replace("VERIFIED_NONE", "INVALID_RESTRICTION"),
            approval.replace("\"selectedGroupIds\": [\"moto-50-125\"]", "\"selectedGroupIds\": null"),
        )) {
            assertFails { mapper.readValue(invalid, VerificationDecisionRequest::class.java) }
        }
    }
}
