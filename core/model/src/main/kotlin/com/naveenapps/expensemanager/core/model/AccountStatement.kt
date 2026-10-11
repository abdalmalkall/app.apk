package com.naveenapps.expensemanager.core.model

import java.math.BigDecimal

/** Positive balances are receivables for customers and payables for suppliers. */
data class StatementMovement(
    val id: String,
    val timestamp: Long,
    val reference: String,
    val description: String,
    val obligation: BigDecimal = BigDecimal.ZERO,
    val payment: BigDecimal = BigDecimal.ZERO,
    val invoiceId: String = id,
)

data class StatementRow(val movement: StatementMovement, val balance: BigDecimal)

data class AccountStatement(
    val name: String,
    val phone: String?,
    val address: String?,
    val from: Long,
    val to: Long,
    val openingBalance: BigDecimal,
    val rows: List<StatementRow>,
    val closingBalance: BigDecimal,
)

fun buildAccountStatement(
    name: String,
    phone: String?,
    address: String?,
    movements: List<StatementMovement>,
    from: Long,
    to: Long,
): AccountStatement {
    require(from <= to)
    // Invoice precedes its own payment at the same timestamp. IDs break all other ties.
    val ordered = movements.sortedWith(compareBy<StatementMovement> { it.timestamp }
        .thenBy { it.invoiceId }.thenBy { if (it.obligation.signum() > 0) 0 else 1 }.thenBy { it.id })
    val opening = ordered.filter { it.timestamp < from }.fold(BigDecimal.ZERO) { balance, item ->
        balance.add(item.obligation).subtract(item.payment)
    }
    var running = opening
    val rows = ordered.filter { it.timestamp in from..to }.map { item ->
        running = running.add(item.obligation).subtract(item.payment)
        StatementRow(item, running)
    }
    return AccountStatement(name, phone, address, from, to, opening, rows, running)
}
