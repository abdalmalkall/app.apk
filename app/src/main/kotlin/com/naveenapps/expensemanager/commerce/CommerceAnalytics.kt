package com.naveenapps.expensemanager.commerce

import java.math.BigDecimal
import java.math.RoundingMode

data class ProductTotals(val quantity: Map<String, BigDecimal>, val baseSales: Long, val grossSales: Long, val revenueBeforeTax: Long, val receivable: Long, val cost: Long?, val profit: Long?)
object CommerceAnalytics {
    /** Largest-remainder allocation: every milliunit is assigned exactly once. */
    fun allocate(amount: Long, weights: Map<Long, BigDecimal>): Map<Long, Long> {
        require(amount >= 0 && weights.values.all { it >= BigDecimal.ZERO })
        val sum = weights.values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (sum == BigDecimal.ZERO) return weights.keys.associateWith { 0L }
        val exact = weights.mapValues { (_, weight) -> BigDecimal.valueOf(amount).multiply(weight).divide(sum, 18, RoundingMode.DOWN) }
        val allocated = exact.mapValues { it.value.setScale(0, RoundingMode.DOWN).longValueExact() }.toMutableMap()
        var left = amount - allocated.values.sum()
        exact.entries.sortedWith(compareByDescending<Map.Entry<Long, BigDecimal>> { it.value.remainder(BigDecimal.ONE) }.thenBy { it.key }).forEach { (key, _) -> if (left > 0) { allocated[key] = allocated.getValue(key) + 1; left-- } }
        check(left == 0L)
        return allocated
    }
    fun product(s: Snapshot, productId: String, from: Long = 0, to: Long = Long.MAX_VALUE): ProductTotals {
        val all = s.items.groupBy { it.invoiceId }
        var base = BigDecimal.ZERO
        var gross = 0L
        var revenue = 0L
        var debt = 0L
        var cost = BigDecimal.ZERO
        var costKnown = true
        val quantities = mutableMapOf<String, BigDecimal>()
        s.invoices.filter { it.created in from..to }.forEach { invoice ->
            val items = all[invoice.id].orEmpty()
            val weights = items.associate { it.id to BigDecimal.valueOf(it.total).multiply(Money.decimal(it.quantity) - Money.decimal(it.returned)).divide(Money.decimal(it.quantity), 18, RoundingMode.HALF_UP) }
            val salesAllocation = allocate(invoice.net, weights)
            val debtAllocation = allocate(invoice.remaining, weights)
            val currentTax = if (invoice.total == 0L) 0L else BigDecimal.valueOf(invoice.tax).multiply(BigDecimal.valueOf(invoice.net)).divide(BigDecimal.valueOf(invoice.total), 0, RoundingMode.HALF_UP).longValueExact()
            val revenueAllocation = allocate(invoice.net - currentTax, weights)
            items.filter { it.productId == productId }.forEach { line ->
                val qty = Money.decimal(line.quantity) - Money.decimal(line.returned)
                quantities[line.unit] = (quantities[line.unit] ?: BigDecimal.ZERO) + qty
                base += weights.getValue(line.id)
                gross += salesAllocation.getValue(line.id)
                revenue += revenueAllocation.getValue(line.id)
                debt += debtAllocation.getValue(line.id)
                if (qty > BigDecimal.ZERO && line.cost == null) costKnown = false
                cost += qty.multiply(BigDecimal.valueOf(line.cost ?: 0))
            }
        }
        val costLong = cost.setScale(0, RoundingMode.HALF_UP).longValueExact()
        return ProductTotals(quantities, base.setScale(0, RoundingMode.HALF_UP).longValueExact(), gross, revenue, debt, if (costKnown) costLong else null, if (costKnown) revenue - costLong else null)
    }
}
