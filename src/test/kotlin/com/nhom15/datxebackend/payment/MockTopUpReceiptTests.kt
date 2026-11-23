package com.nhom15.datxebackend.payment

import com.nhom15.datxebackend.common.exception.ConflictException
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.payment.entity.*
import com.nhom15.datxebackend.payment.exception.InvalidTopUpAmountException
import com.nhom15.datxebackend.payment.service.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.util.Optional

/** Receipt V20/V21 phải chặn retry cả ở luồng admin lẫn self-service. */
class MockTopUpReceiptTests {
    private data class Credit(val ownerId: Long, val owner: WalletOwner, val type: WalletType, val amount: Long)

    private fun row(
        singleLong: Long? = null,
        longs: Map<String, Long> = emptyMap(),
        strings: Map<String, String> = emptyMap(),
    ): ResultSet = mock(ResultSet::class.java) { inv ->
        when (inv.method.name) {
            "getLong" -> {
                val column = inv.arguments[0]
                if (column is Number) singleLong ?: 0L else longs[column as String] ?: 0L
            }
            "getString" -> (inv.arguments[0] as? String)?.let(strings::get)
            else -> null
        }
    }

    private fun ledgerEntry() = WalletTransaction(
        walletType = "CASH", amount = 50_000, legacyType = "DEPOSIT",
        transactionType = TransactionType.TOP_UP, balanceAfter = 50_000,
    ).apply { id = 9 }

    @Test
    fun `same driver key and wallet does not credit twice but a different wallet conflicts`() {
        val credits = mutableListOf<Credit>()
        val wallets = mock(WalletService::class.java) { inv ->
            if (inv.method.name == "topUp") {
                val a = inv.arguments
                credits += Credit(a[0] as Long, a[1] as WalletOwner, a[2] as WalletType, a[3] as Long)
                ledgerEntry()
            } else null
        }
        val drivers = mock(DriverRepository::class.java) { inv ->
            if (inv.method.name == "findByIdForUpdate") Optional.of(Driver()) else null
        }
        var receipt: Pair<Long, String>? = null
        val jdbc = mock(JdbcTemplate::class.java) { inv ->
            when (inv.method.name) {
                "query" -> {
                    @Suppress("UNCHECKED_CAST")
                    val mapper = inv.getArgument<RowMapper<Pair<Long, String>>>(1)
                    val current = receipt
                    if (current == null) {
                        emptyList<Any>()
                    } else {
                        listOf(
                            mapper.mapRow(
                                row(longs = mapOf("amount" to current.first), strings = mapOf("wallet_type" to current.second)),
                                0,
                            ),
                        )
                    }
                }
                "update" -> 1
                else -> null
            }
        }
        val service = DriverMockTopUpService(wallets, drivers, jdbc)

        service.topUpForOwner(7, 50_000, "admin-key", WalletType.CASH)
        receipt = 50_000L to "CASH"
        service.topUpForOwner(7, 50_000, "admin-key", WalletType.CASH)
        assertEquals(listOf(Credit(7, WalletOwner.DRIVER, WalletType.CASH, 50_000)), credits)

        assertThrows(ConflictException::class.java) {
            service.topUpForOwner(7, 50_000, "admin-key", WalletType.CREDIT)
        }
        assertThrows(ConflictException::class.java) {
            service.topUpForOwner(7, 60_000, "admin-key", WalletType.CASH)
        }
        assertThrows(InvalidTopUpAmountException::class.java) {
            service.topUpForOwner(7, 0, "other-key", WalletType.CASH)
        }
        assertEquals(1, credits.size)
    }

    @Test
    fun `customer admin top up replays receipt instead of crediting again`() {
        val credits = mutableListOf<Credit>()
        val wallets = mock(WalletService::class.java) { inv ->
            if (inv.method.name == "topUp") {
                val a = inv.arguments
                credits += Credit(a[0] as Long, a[1] as WalletOwner, a[2] as WalletType, a[3] as Long)
                ledgerEntry()
            } else null
        }
        val customers = mock(CustomerRepository::class.java) { inv ->
            if (inv.method.name == "findByIdForUpdate") Optional.of(Customer()) else null
        }
        var receipt: Long? = null
        val jdbc = mock(JdbcTemplate::class.java) { inv ->
            when (inv.method.name) {
                "query" -> {
                    @Suppress("UNCHECKED_CAST")
                    val mapper = inv.getArgument<RowMapper<Long>>(1)
                    val current = receipt
                    if (current == null) emptyList<Any>() else listOf(mapper.mapRow(row(singleLong = current), 0))
                }
                "update" -> 1
                else -> null
            }
        }
        val service = CustomerMockTopUpService(wallets, customers, jdbc)

        service.topUpForOwner(11, 50_000, "customer-key")
        receipt = 50_000L
        service.topUpForOwner(11, 50_000, "customer-key")
        assertEquals(listOf(Credit(11, WalletOwner.CUSTOMER, WalletType.CUSTOMER, 50_000)), credits)
        assertThrows(ConflictException::class.java) { service.topUpForOwner(11, 70_000, "customer-key") }
    }
}
