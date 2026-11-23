package com.nhom15.datxebackend.driver.verification

import com.nhom15.datxebackend.common.api.ApiError
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.exception.AppException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.mockito.Mockito.mock

class VerificationErrorContractTests {
    private val controller = DriverVerificationWorkflowController(mock(DriverVerificationWorkflowService::class.java))

    @Test
    fun `workflow and provider errors support legacy and nested clients`() {
        val workflow = VerificationWorkflowException(409, "VERIFY_BUSY", "Thử lại")
        val application = AppException(ErrorCode.SYSTEM_UNAVAILABLE, details = mapOf("step" to "face"))
        val workflowResponse = controller.handleWorkflowError(workflow)
        val applicationResponse = controller.handleAppError(application)

        assertEquals(409, workflowResponse.statusCode.value())
        assertEquals(application.httpStatus.value(), applicationResponse.statusCode.value())
        for (response in listOf(workflowResponse, applicationResponse)) {
            val body = response.body!!
            val error = body["error"] as ApiError
            assertEquals(false, body["success"])
            assertEquals(error.code, body["errorCode"])
            assertEquals(error.code, body["error_code"])
            assertEquals(error.message, body["message"])
        }
        assertEquals(application.details, (applicationResponse.body!!["error"] as ApiError).details)
    }
}
