package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.entity.*
import com.nhom15.datxebackend.payment.exception.*
import com.nhom15.datxebackend.payment.repository.*
import com.nhom15.datxebackend.payment.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Optional

class WalletServiceTests {
    private val wallets = mock(WalletRepository::class.java)
    private val ledger = mock(WalletTransactionRepository::class.java)
    private val drivers = mock(DriverRepository::class.java)
    private val customers = mock(CustomerRepository::class.java)
    private val service = WalletService(wallets, ledger, drivers, customers, 50_000)

    private fun wallet(owner: WalletOwner, type: WalletType, balance: Long): Wallet {
        val result = Wallet(7, owner, type, balance)
        `when`(wallets.lockWallet(7, owner, type)).thenReturn(result)
        `when`(drivers.findByIdForUpdate(7)).thenReturn(Optional.of(Driver()))
        `when`(customers.findByIdForUpdate(7)).thenReturn(Optional.of(Customer()))
        `when`(ledger.save(any(WalletTransaction::class.java))).thenAnswer { it.arguments[0] }
        return result
    }

    @Test fun `cash completion debits only commission from credit`() {
        val credit = wallet(WalletOwner.DRIVER, WalletType.CREDIT, 50_000)
        val method = service.settle(42, 7, 7, 100_000, com.nhom15.datxebackend.common.enums.PaymentMethod.CASH)
        assertEquals(com.nhom15.datxebackend.common.enums.PaymentMethod.CASH, method)
        assertEquals(30_000, credit.balance)
        val transaction = mockingDetails(ledger).invocations.single { it.method.name == "save" }.arguments[0] as WalletTransaction
        assertEquals(-20_000, transaction.amount)
        assertEquals(TransactionType.COMMISSION_FEE, transaction.transactionType)
        verify(wallets, never()).lockWallet(7, WalletOwner.CUSTOMER, WalletType.CUSTOMER)
    }

    @Test fun `wallet completion transfers 80 percent to cash wallet`() {
        val customer = wallet(WalletOwner.CUSTOMER, WalletType.CUSTOMER, 150_000)
        val cash = wallet(WalletOwner.DRIVER, WalletType.CASH, 0)
        val method = service.settle(42, 7, 7, 100_000, com.nhom15.datxebackend.common.enums.PaymentMethod.WALLET)
        assertEquals(com.nhom15.datxebackend.common.enums.PaymentMethod.WALLET, method)
        assertEquals(50_000, customer.balance)
        assertEquals(80_000, cash.balance)
        verify(ledger, times(2)).save(any(WalletTransaction::class.java))
    }

    @Test fun `wallet shortfall at completion falls back to cash commission`() {
        val customer = wallet(WalletOwner.CUSTOMER, WalletType.CUSTOMER, 99_999)
        val cash = wallet(WalletOwner.DRIVER, WalletType.CASH, 0)
        val credit = wallet(WalletOwner.DRIVER, WalletType.CREDIT, 50_000)
        val method = service.settle(42, 7, 7, 100_000, com.nhom15.datxebackend.common.enums.PaymentMethod.WALLET)
        assertEquals(com.nhom15.datxebackend.common.enums.PaymentMethod.CASH, method)
        assertEquals(99_999, customer.balance)
        assertEquals(0, cash.balance)
        assertEquals(30_000, credit.balance)
        val transaction = mockingDetails(ledger).invocations.single { it.method.name == "save" }.arguments[0] as WalletTransaction
        assertEquals(-20_000, transaction.amount)
        assertEquals(TransactionType.COMMISSION_FEE, transaction.transactionType)
        assertTrue(transaction.description!!.contains("tự động thu tiền mặt"))
        verify(wallets, never()).lockWallet(7, WalletOwner.DRIVER, WalletType.CASH)
    }

    @Test fun `driver must have minimum credit and sufficient commission`() {
        wallet(WalletOwner.DRIVER, WalletType.CREDIT, 49_999)
        assertThrows(CreditWalletBelowMinimumException::class.java) { service.requireEligibleDriver(7) }
        wallet(WalletOwner.DRIVER, WalletType.CREDIT, 50_000)
        service.requireEligibleDriver(7)
        assertThrows(InsufficientCreditBalanceException::class.java) { service.requireEligibleDriver(7, 500_000) }
    }

    @Test fun `hold is a pending negative ledger entry`() {
        val cash = wallet(WalletOwner.DRIVER, WalletType.CASH, 80_000)
        val entry = service.hold(7, 50_000, 9)
        assertEquals(30_000, cash.balance)
        assertEquals(WalletStatus.PENDING, entry.status)
        assertEquals(-50_000, entry.amount)
        assertEquals(9L, entry.withdrawalId)
    }

    @Test fun `commission cannot overflow on maximum long fare`() {
        assertEquals(Long.MAX_VALUE / 5, WalletService.commission(Long.MAX_VALUE))
        assertEquals(20_000, WalletService.commission(100_003))
    }

    @Test fun `commission keeps vnd precision and never rounds to thousand`() {
        assertEquals(6_200, WalletService.commission(31_000))
        assertEquals(6_250, WalletService.commission(31_250))
    }

    @Test fun `overflow and negative top up do not mutate balance`() {
        val cash = wallet(WalletOwner.DRIVER, WalletType.CASH, Long.MAX_VALUE)
        assertThrows(RuntimeException::class.java) { service.topUp(7, WalletOwner.DRIVER, WalletType.CASH, 1) }
        assertEquals(Long.MAX_VALUE, cash.balance)
        assertThrows(RuntimeException::class.java) { service.topUp(7, WalletOwner.DRIVER, WalletType.CASH, -1) }
        verify(ledger, never()).save(any(WalletTransaction::class.java))
    }

    @Test fun `cash commission records debt and blocks driver until minimum is restored`() {
        val credit = wallet(WalletOwner.DRIVER, WalletType.CREDIT, 10_000)
        val driver = drivers.findByIdForUpdate(7).get()
        service.settle(42, 7, 7, 100_000, com.nhom15.datxebackend.common.enums.PaymentMethod.CASH)
        assertEquals(-10_000, credit.balance)
        assertEquals(com.nhom15.datxebackend.common.enums.DriverStatus.BLOCKED, driver.status)
        service.topUp(7, WalletOwner.DRIVER, WalletType.CREDIT, 5_000)
        assertEquals(-5_000, credit.balance)
        assertEquals(com.nhom15.datxebackend.common.enums.DriverStatus.BLOCKED, driver.status)
        service.topUp(7, WalletOwner.DRIVER, WalletType.CREDIT, 55_000)
        assertEquals(50_000, credit.balance)
        assertEquals(com.nhom15.datxebackend.common.enums.DriverStatus.OFFLINE, driver.status)
    }

    @Test fun `missing owner and missing wallet have distinct errors`() {
        `when`(drivers.findByIdForUpdate(7)).thenReturn(Optional.empty())
        assertThrows(UserNotFoundException::class.java) { service.topUp(7, WalletOwner.DRIVER, WalletType.CREDIT, 1) }
        `when`(drivers.findByIdForUpdate(7)).thenReturn(Optional.of(Driver()))
        assertThrows(WalletNotFoundException::class.java) { service.topUp(7, WalletOwner.DRIVER, WalletType.CREDIT, 1) }
    }

    @Test fun `wrong wallet owner and invalid top up amounts are rejected`() {
        assertThrows(InvalidWalletTypeException::class.java) { service.topUp(7, WalletOwner.CUSTOMER, WalletType.CREDIT, 1) }
        for (amount in listOf(-1L, 0L, 50_000_001L)) {
            assertThrows(InvalidTopUpAmountException::class.java) { service.topUp(7, WalletOwner.DRIVER, WalletType.CREDIT, amount) }
        }
        val cash = wallet(WalletOwner.DRIVER, WalletType.CASH, 0)
        service.topUp(7, WalletOwner.DRIVER, WalletType.CASH, 50_000_000)
        assertEquals(50_000_000, cash.balance)
    }

    @Test fun `cash cannot be overdrawn and withdrawal must be positive`() {
        val cash = wallet(WalletOwner.DRIVER, WalletType.CASH, 10_000)
        assertThrows(InsufficientCashBalanceException::class.java) { service.hold(7, 20_000, 9) }
        assertThrows(InvalidAmountException::class.java) { service.hold(7, 0, 9) }
        assertEquals(10_000, cash.balance)
    }

    @Test fun `unsupported payment method cannot mutate any wallet`() {
        assertThrows(PaymentMethodMismatchException::class.java) {
            service.settle(42, 7, 7, 100_000, com.nhom15.datxebackend.common.enums.PaymentMethod.VNPAY)
        }
        verifyNoInteractions(wallets, ledger)
    }
    @Test fun `credit status uses the server minimum and includes debt in shortfall`() {
        val credit = wallet(WalletOwner.DRIVER, WalletType.CREDIT, -10_000)
        val driver = drivers.findByIdForUpdate(7).get()
        driver.status = com.nhom15.datxebackend.common.enums.DriverStatus.BLOCKED
        val debt = service.creditStatus(7)
        assertEquals(60_000, debt.shortfall)
        assertFalse(debt.canReceive)
        credit.balance = 50_000
        driver.status = com.nhom15.datxebackend.common.enums.DriverStatus.OFFLINE
        assertTrue(service.creditStatus(7).canReceive)
    }
}
