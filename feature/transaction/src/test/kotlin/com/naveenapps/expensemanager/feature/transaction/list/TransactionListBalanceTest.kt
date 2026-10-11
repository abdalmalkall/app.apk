package com.naveenapps.expensemanager.feature.transaction.list

import com.google.common.truth.Truth.assertThat
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.Transaction
import com.naveenapps.expensemanager.core.model.TransactionType
import java.util.Date
import org.junit.Test

class TransactionListBalanceTest {

    @Test
    fun `customer debt is receivable while supplier debt is payable`() {
        val transactions = listOf(
            transaction("customer-debt", TransactionType.DEBT, 100.0, customerName = "Ahmed"),
            transaction("supplier-debt", TransactionType.DEBT, 200.0, supplierId = "supplier-1"),
            transaction("cash-purchase", TransactionType.EXPENSE, 30.0),
        )

        assertThat(transactions.toTransactionSum()).isEqualTo(-130.0)
    }

    private fun transaction(
        id: String,
        type: TransactionType,
        amount: Double,
        customerName: String? = null,
        supplierId: String? = null,
    ) = Transaction(
        id = id,
        notes = "",
        categoryId = "category",
        fromAccountId = "account",
        toAccountId = null,
        amount = Amount(amount),
        imagePath = "",
        type = type,
        customerName = customerName,
        customerId = if (customerName == null) null else "customer-1",
        supplierId = supplierId,
        supplierName = supplierId?.let { "Supplier" },
        createdOn = Date(1_700_000_000_000L),
        updatedOn = Date(1_700_000_000_000L),
    )
}
