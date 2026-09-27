package com.naveenapps.expensemanager.feature.transaction.customer

import androidx.compose.runtime.Stable
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.TransactionUiItem

@Stable
data class CustomerLedgerState(
    val searchQuery: String = "",
    val allCustomers: List<CustomerLedgerItem> = emptyList(),
    val filteredCustomers: List<CustomerLedgerItem> = emptyList(),
)

@Stable
data class CustomerLedgerItem(
    val customerName: String,
    val totalAmount: Amount,
    val transactionCount: Int,
    val transactions: List<TransactionUiItem>,
)
