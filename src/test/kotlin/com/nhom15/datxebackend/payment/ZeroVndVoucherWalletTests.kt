package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.common.domain.GeoPoint
import com.nhom15.datxebackend.common.domain.Location
import com.nhom15.datxebackend.common.domain.Money
import com.nhom15.datxebackend.common.enums.PaymentMethod
import com.nhom15.datxebackend.common.enums.RideStatus
import com.nhom15.datxebackend.common.enums.Transmission
import com.nhom15.datxebackend.common.enums.VehicleType
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.entity.TransactionType
import com.nhom15.datxebackend.payment.entity.Wallet
import com.nhom15.datxebackend.payment.entity.WalletOwner
import com.nhom15.datxebackend.payment.entity.WalletTransaction
import com.nhom15.datxebackend.payment.entity.WalletType
import com.nhom15.datxebackend.payment.repository.WalletRepository
import com.nhom15.datxebackend.payment.repository.WalletTransactionRepository
import com.nhom15.datxebackend.payment.service.PaymentService
import com.nhom15.datxebackend.payment.service.WalletService
import com.nhom15.datxebackend.ride.entity.Ride
import com.nhom15.datxebackend.ride.entity.RideType
import com.nhom15.datxebackend.ride.event.RideCompleted
import com.nhom15.datxebackend.ride.repository.RideRepository
import com.nhom15.datxebackend.voucher.service.PromoBudgetService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Optional

/** Hồi quy quyết toán chuyến voucher 0 VND và bù cước trên cả CASH và WALLET. */
class ZeroVndVoucherWalletTests {
    private val wallets = mock(WalletRepository::class.java)
    private val ledger = mock(WalletTransactionRepository::class.java)
    private val drivers = mock(DriverRepository::class.java)
    private val customers = mock(CustomerRepository::class.java)
    private val service = WalletService(wallets, ledger, drivers, customers, 50_000)

    private fun wallet(userId: Long, owner: WalletOwner, type: WalletType, balance: Long): Wallet {
        val result = Wallet(userId, owner, type, balance)
        Mockito.`when`(wallets.lockWallet(userId, owner, type)).thenReturn(result)
        if (owner == WalletOwner.DRIVER) {
            Mockito.`when`(drivers.findByIdForUpdate(userId)).thenReturn(Optional.of(Driver()))
        } else {
            Mockito.`when`(customers.findByIdForUpdate(userId)).thenReturn(Optional.of(Customer()))
        }
        Mockito.`when`(ledger.save(any(WalletTransaction::class.java))).thenAnswer { it.arguments[0] }
        return result
    }

    @Test
    fun `0 vnd cash voucher ride does not debit credit and credits full driver net to cash wallet`() {
        val cash = wallet(7, WalletOwner.DRIVER, WalletType.CASH, 10_000)
        val credit = wallet(7, WalletOwner.DRIVER, WalletType.CREDIT, 50_000)

        val method = service.settle(101, 1, 7, fare = 0, method = PaymentMethod.CASH, rawFare = 50_000)

        assertEquals(PaymentMethod.CASH, method)
        assertEquals(50_000, credit.balance)
        assertEquals(50_000, cash.balance)
        val saved = Mockito.mockingDetails(ledger).invocations
            .filter { it.method.name == "save" }
            .map { it.arguments[0] as WalletTransaction }
        assertEquals(1, saved.size)
        assertEquals(TransactionType.RIDE_PAYMENT, saved.single().transactionType)
        assertEquals(40_000, saved.single().amount)
    }

    @Test
    fun `0 vnd wallet voucher ride still credits full driver net to cash wallet`() {
        val customer = wallet(1, WalletOwner.CUSTOMER, WalletType.CUSTOMER, 25_000)
        val cash = wallet(7, WalletOwner.DRIVER, WalletType.CASH, 0)

        val method = service.settle(102, 1, 7, fare = 0, method = PaymentMethod.WALLET, rawFare = 50_000)

        assertEquals(PaymentMethod.WALLET, method)
        assertEquals(25_000, customer.balance)
        assertEquals(40_000, cash.balance)
        val saved = Mockito.mockingDetails(ledger).invocations
            .filter { it.method.name == "save" }
            .map { it.arguments[0] as WalletTransaction }
        assertEquals(1, saved.size)
        assertEquals(TransactionType.RIDE_PAYMENT, saved.single().transactionType)
        assertEquals(40_000, saved.single().amount)
    }

    @Test
    fun `wallet shortfall with partial voucher falls back to cash and still subsidizes shortfall below driver net`() {
        val customer = wallet(1, WalletOwner.CUSTOMER, WalletType.CUSTOMER, 5_000)
        val cash = wallet(7, WalletOwner.DRIVER, WalletType.CASH, 0)
        val credit = wallet(7, WalletOwner.DRIVER, WalletType.CREDIT, 50_000)

        val method = service.settle(103, 1, 7, fare = 20_000, method = PaymentMethod.WALLET, rawFare = 50_000)

        assertEquals(PaymentMethod.CASH, method)
        assertEquals(5_000, customer.balance)
        assertEquals(50_000, credit.balance)
        assertEquals(20_000, cash.balance)
    }

    @Test
    fun `payment completion spends promo budget and settles with unrounded rawFare`() {
        val jdbc = mock(JdbcTemplate::class.java)
        val rides = mock(RideRepository::class.java)
        val mockWallets = mock(WalletService::class.java)
        val promoBudget = mock(PromoBudgetService::class.java)
        val ride = Ride(
            customerId = 1,
            vehicleId = 2,
            driverId = 7,
            type = RideType.INSTANT,
            pickup = Location(GeoPoint(21.0, 105.0), "Đón"),
            dropoff = Location(GeoPoint(21.1, 105.1), "Trả"),
            vehicleType = VehicleType.CAR,
            transmission = Transmission.AUTO,
            fareEstimate = 31_250,
            discountAmount = 31_250,
            finalFare = 0,
            paymentMethod = PaymentMethod.WALLET,
            status = RideStatus.COMPLETED,
        ).apply { id = 501 }
        Mockito.`when`(rides.findByIdForUpdate(501)).thenReturn(Optional.of(ride))
        Mockito.`when`(jdbc.update("INSERT INTO ride_wallet_settlements(ride_id) VALUES (?) ON CONFLICT DO NOTHING", 501L))
            .thenReturn(1)
        Mockito.`when`(mockWallets.settle(501, 1, 7, 0, PaymentMethod.WALLET, 31_250)).thenReturn(PaymentMethod.WALLET)

        val paymentService = PaymentService(
            jdbc,
            mock(DriverRepository::class.java),
            rides,
            mockWallets,
            mock(ApplicationEventPublisher::class.java),
            promoBudget,
        )
        paymentService.onRideCompleted(RideCompleted(501, 1, 7, Money(0)))

        verify(promoBudget).spend(501L)
        verify(mockWallets).settle(501, 1, 7, 0, PaymentMethod.WALLET, 31_250)
    }
}
