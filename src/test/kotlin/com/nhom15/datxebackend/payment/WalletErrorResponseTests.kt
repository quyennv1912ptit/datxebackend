package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.common.exception.GlobalExceptionHandler
import com.nhom15.datxebackend.payment.exception.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException

class WalletErrorResponseTests {
    private val handler = GlobalExceptionHandler()

    @Test fun `lock timeout returns busy response and retry hint`() {
        val response = handler.handleLockBusy(CannotAcquireLockException("lock timeout"))
        assertEquals(503, response.statusCode.value())
        assertEquals("3", response.headers.getFirst("Retry-After"))
        assertEquals("WALLET_BUSY", response.body!!.errorCode)
        assertEquals(503, response.body!!.status)
        assertNotNull(response.body!!.timestamp)
    }

    @Test fun `data integrity violation returns conflict instead of unavailable`() {
        val response = handler.handleDataConflict(DataIntegrityViolationException("duplicate key"))
        assertEquals(409, response.statusCode.value())
        assertEquals("CONFLICT", response.body!!.errorCode)
        assertEquals(409, response.body!!.status)
    }

    @Test fun `specific error codes remain available to existing mobile error parser`() {
        val response = handler.handleApp(InsufficientCustomerBalanceException())
        assertEquals(409, response.statusCode.value())
        assertEquals("INSUFFICIENT_CUSTOMER_BALANCE", response.body!!.errorCode)
        assertEquals(response.body!!.errorCode, response.body!!.error!!.code)
        assertEquals(response.body!!.message, response.body!!.error!!.message)
        val json = tools.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(response.body)
        assertTrue(json.contains("\"error_code\":\"INSUFFICIENT_CUSTOMER_BALANCE\""))
        assertTrue(json.contains("\"status\":409"))
    }
}
