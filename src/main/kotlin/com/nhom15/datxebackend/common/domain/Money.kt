package com.nhom15.datxebackend.common.domain

@JvmInline
value class Money(val amount: Long) : Comparable<Money> {

    operator fun plus(other: Money) = Money(amount + other.amount)
    operator fun minus(other: Money) = Money(amount - other.amount)
    operator fun times(factor: Int) = Money(amount * factor)

    fun percent(p: Int) = Money(amount * p / 100)

    fun isZero() = amount == 0L
    fun isPositive() = amount > 0L

    override fun compareTo(other: Money): Int = amount.compareTo(other.amount)

    override fun toString(): String = "${amount}đ"

    companion object {
        val ZERO = Money(0)
    }
}