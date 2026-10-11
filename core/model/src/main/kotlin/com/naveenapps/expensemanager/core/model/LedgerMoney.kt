package com.naveenapps.expensemanager.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/** Exact decimal arithmetic. Double conversion is confined to the existing Room/UI boundary.
 * Existing amounts keep their historical precision; new ledger amounts have at most 3 decimals.
 */
object LedgerMoney {
    fun decimal(legacy: Double): BigDecimal {
        require(legacy.isFinite()) { "Amount must be finite" }
        return BigDecimal.valueOf(legacy)
    }

    fun exact(legacy: Double, stored: String?): BigDecimal {
        val exact = stored?.let(::BigDecimal)
        // A legacy editor may replace the amount while retaining an old decimal value.
        return if (exact != null && exact.toDouble() == legacy) exact else decimal(legacy)
    }

    fun totalExact(values: Iterable<BigDecimal>): BigDecimal = values.fold(BigDecimal.ZERO, BigDecimal::add)

    fun input(text: String): BigDecimal? = runCatching {
        val normalized = text.trim().map { char ->
            when {
                char == '٫' -> '.'
                char.isDigit() -> Character.digit(char, 10).toString().single()
                else -> char
            }
        }.joinToString("")
        require(normalized.matches(Regex("[0-9]+(\\.[0-9]{1,3})?")))
        BigDecimal(normalized).also { require(it.precision() <= 15) }
    }.getOrNull()

    fun validNewAmount(value: Double): Boolean = runCatching {
        val decimal = decimal(value)
        decimal.signum() > 0 && decimal.stripTrailingZeros().scale() <= 3 && decimal.precision() <= 15
    }.getOrDefault(false)

    /** Validate the exact value that will be applied, rather than only its rounded Double. */
    fun validNewAmount(value: Double, stored: String?): Boolean = runCatching {
        require(value.isFinite())
        val amount = stored?.let(::BigDecimal) ?: decimal(value)
        amount.toDouble() == value && amount.signum() > 0 &&
            amount.stripTrailingZeros().scale() <= 3 && amount.precision() <= 15
    }.getOrDefault(false)

    fun total(values: Iterable<Double>): BigDecimal = values.fold(BigDecimal.ZERO) { total, value ->
        total.add(decimal(value))
    }

    fun add(balance: Double, delta: Double): Double = decimal(balance).add(decimal(delta)).toDouble()
    fun subtract(balance: Double, delta: Double): Double = decimal(balance).subtract(decimal(delta)).toDouble()
    fun display(value: BigDecimal): String = value.setScale(3, RoundingMode.HALF_UP).toPlainString()
}
