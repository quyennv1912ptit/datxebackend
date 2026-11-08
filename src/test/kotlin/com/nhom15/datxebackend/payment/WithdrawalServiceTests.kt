package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.entity.*
import com.nhom15.datxebackend.payment.repository.*
import com.nhom15.datxebackend.payment.service.*
import com.nhom15.datxebackend.common.exception.ConflictException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

class WithdrawalServiceTests {
    @Test fun `reject refunds once and conflicting approve fails`() {
        val requests = mock(WithdrawalRepository::class.java)
        val transactions = mock(WalletTransactionRepository::class.java)
        val wallets = mock(WalletService::class.java)
        val request = WithdrawalRequest(7, 50_000, "request-1", id = 9)
        val debit = WalletTransaction(driverId = 7, walletType = "INCOME", amount = -50_000,
            legacyType = "WITHDRAW", transactionType = TransactionType.WITHDRAW,
            status = WalletStatus.PENDING, balanceAfter = 0, withdrawalId = 9)
        `when`(requests.lockRequest(9)).thenReturn(request)
        `when`(transactions.findByWithdrawalIdAndTransactionType(9, TransactionType.WITHDRAW)).thenReturn(debit)
        val service = WithdrawalService(requests, transactions, mock(DriverRepository::class.java), wallets, mock(org.springframework.context.ApplicationEventPublisher::class.java))
        service.review(9, 1, WithdrawalAction.REJECT, "Sai số tài khoản")
        assertThrows(com.nhom15.datxebackend.payment.exception.InvalidWithdrawalActionException::class.java) { service.review(9, 1, WithdrawalAction.REJECT, "Sai số tài khoản") }
        verify(wallets, times(1)).release(7, 50_000, 9)
        assertEquals(WalletStatus.FAILED, debit.status)
        assertEquals(WithdrawalStatus.REJECTED, request.status)
        assertEquals("Sai số tài khoản", request.rejectReason)
        assertThrows(com.nhom15.datxebackend.payment.exception.InvalidWithdrawalActionException::class.java) { service.review(9, 1, WithdrawalAction.APPROVE) }
    }

    @Test fun `approve updates the held transaction and never touches wallet balance`() {
        val requests = mock(WithdrawalRepository::class.java)
        val transactions = mock(WalletTransactionRepository::class.java)
        val wallets = mock(WalletService::class.java)
        val events = mock(org.springframework.context.ApplicationEventPublisher::class.java)
        val request = WithdrawalRequest(7, 500_000, "approve-1", id = 10)
        val debit = WalletTransaction(driverId = 7, walletType = "CASH", amount = -500_000,
            legacyType = "WITHDRAW", transactionType = TransactionType.WITHDRAW,
            status = WalletStatus.PENDING, balanceAfter = 0, withdrawalId = 10)
        `when`(requests.lockRequest(10)).thenReturn(request)
        `when`(transactions.findByWithdrawalIdAndTransactionType(10, TransactionType.WITHDRAW)).thenReturn(debit)
        val service = WithdrawalService(requests, transactions, mock(DriverRepository::class.java), wallets, events)
        service.review(10, 3, WithdrawalAction.APPROVE)
        assertEquals(WithdrawalStatus.SUCCESS, request.status)
        assertEquals(WalletStatus.SUCCESS, debit.status)
        assertEquals(3L, request.reviewedBy)
        assertNotNull(request.reviewedAt)
        assertNull(request.rejectReason)
        verifyNoInteractions(wallets)
        assertThrows(com.nhom15.datxebackend.payment.exception.InvalidWithdrawalActionException::class.java) { service.review(10, 4, WithdrawalAction.APPROVE) }
        assertThrows(com.nhom15.datxebackend.payment.exception.InvalidWithdrawalActionException::class.java) { service.review(10, 4, WithdrawalAction.REJECT, "Sai số tài khoản") }
        assertEquals(1, mockingDetails(events).invocations.size)
    }

    @Test fun `reject requires a reason before refunding anything`() {
        val requests = mock(WithdrawalRepository::class.java)
        val transactions = mock(WalletTransactionRepository::class.java)
        val wallets = mock(WalletService::class.java)
        `when`(requests.lockRequest(10)).thenReturn(WithdrawalRequest(7, 500_000, "reject-1", id = 10))
        val service = WithdrawalService(requests, transactions, mock(DriverRepository::class.java), wallets, mock(org.springframework.context.ApplicationEventPublisher::class.java))
        for (reason in listOf(null, "  ", "x".repeat(501))) {
            assertThrows(com.nhom15.datxebackend.common.exception.BadRequestException::class.java) { service.review(10, 3, WithdrawalAction.REJECT, reason) }
        }
        verifyNoInteractions(wallets, transactions)
    }

    @Test fun `admin list filters by status and joins driver bank details`() {
        val requests = mock(WithdrawalRepository::class.java)
        val drivers = mock(DriverRepository::class.java)
        val service = WithdrawalService(requests, mock(WalletTransactionRepository::class.java), drivers,
            mock(WalletService::class.java), mock(org.springframework.context.ApplicationEventPublisher::class.java))
        val request = WithdrawalRequest(7, 50_000, "pending-1", id = 9)
        val driver = Driver().apply {
            id = 7
            fullName = "Tài xế A"
            phoneNumber = "0900000000"
            bankName = "Vietcombank"
            bankAccountNumber = "0123456789"
        }
        `when`(requests.findByStatus(WithdrawalStatus.PENDING, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"))))
            .thenReturn(PageImpl(listOf(request), PageRequest.of(0, 20), 1))
        `when`(drivers.findAllById(setOf(7L))).thenReturn(listOf(driver))

        val page = service.adminList(WithdrawalStatus.PENDING, 0, 20)

        assertEquals(1, page.content.size)
        assertEquals(9L, page.content.single().id)
        assertEquals("Tài xế A", page.content.single().driverName)
        assertEquals("0900000000", page.content.single().driverPhoneNumber)
        assertEquals("Vietcombank", page.content.single().bankName)
        assertEquals("0123456789", page.content.single().bankAccountNumber)
        assertEquals(WithdrawalStatus.PENDING, page.content.single().status)
        assertFalse(page.hasNext)
    }

    @Test fun `admin list validates page and size`() {
        val service = WithdrawalService(mock(WithdrawalRepository::class.java), mock(WalletTransactionRepository::class.java),
            mock(DriverRepository::class.java), mock(WalletService::class.java),
            mock(org.springframework.context.ApplicationEventPublisher::class.java))
        assertThrows(com.nhom15.datxebackend.common.exception.BadRequestException::class.java) { service.adminList(null, -1, 20) }
        assertThrows(com.nhom15.datxebackend.common.exception.BadRequestException::class.java) { service.adminList(null, 0, 0) }
        assertThrows(com.nhom15.datxebackend.common.exception.BadRequestException::class.java) { service.adminList(null, 0, 101) }
    }
}
