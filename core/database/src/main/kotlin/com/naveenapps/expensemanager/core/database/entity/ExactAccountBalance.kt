package com.naveenapps.expensemanager.core.database.entity

import com.naveenapps.expensemanager.core.model.LedgerMoney
import java.math.BigDecimal

/** Exact canonical balance, with the REAL column retained for legacy readers. */
fun AccountEntity.adjustBalance(delta: BigDecimal): AccountEntity {
    val updated = LedgerMoney.exact(amount, amountDecimal).add(delta)
    require(updated.toDouble().isFinite()) { "Account balance overflow" }
    return copy(amount = updated.toDouble(), amountDecimal = updated.toPlainString())
}
