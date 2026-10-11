package com.naveenapps.expensemanager.feature.transaction.customer

import androidx.compose.runtime.Stable
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.CustomerPayment
import com.naveenapps.expensemanager.core.model.Customer
import com.naveenapps.expensemanager.core.model.TransactionUiItem

@Stable
data class CustomerLedgerState(
    val searchQuery: String = "",
    val debtFilter: com.naveenapps.expensemanager.feature.transaction.ledger.DebtFilter = com.naveenapps.expensemanager.feature.transaction.ledger.DebtFilter.ALL,
    val sort: com.naveenapps.expensemanager.feature.transaction.ledger.LedgerSort = com.naveenapps.expensemanager.feature.transaction.ledger.LedgerSort.LAST_ACTIVITY,
    val isLoading: Boolean = true,
    val message: Int? = null,
    val totalOutstanding: String = "",
    val statement: com.naveenapps.expensemanager.core.model.AccountStatement? = null,
    val statementMovements: List<com.naveenapps.expensemanager.core.model.StatementMovement> = emptyList(),
    val allCustomers: List<CustomerLedgerItem> = emptyList(),
    val filteredCustomers: List<CustomerLedgerItem> = emptyList(),
    val expandedCustomerId: String? = null,
    val paymentDraft: CustomerPaymentDraft? = null,
    val customerDraft: CustomerDraft? = null,
    val accounts: List<com.naveenapps.expensemanager.core.model.Account> = emptyList(),
)

@Stable
data class CustomerLedgerItem(
    val id: String,
    val customerName: String,
    val phone: String?,
    val totalSales: Amount,
    val totalPaid: Amount,
    val totalAmount: Amount,
    val transactionCount: Int,
    val transactions: List<CustomerInvoiceItem>,
    val address: String? = null,
    val notes: String? = null,
    val creditLimitMilliunits: Long = 0L,
    val profile: Customer? = null,
    val lastActivity: Long = 0L,
    val movements: List<com.naveenapps.expensemanager.core.model.StatementMovement> = emptyList(),
)

data class CustomerInvoiceItem(
    val transaction: TransactionUiItem,
    val paidAmount: Amount,
    val remainingAmount: Amount,
    val paymentStatus: CustomerPaymentStatus,
    val payments: List<CustomerPayment>,
)

enum class CustomerPaymentStatus {
    UNPAID,
    PARTIALLY_PAID,
    PAID,
}

data class CustomerPaymentDraft(
    val paymentId: String,
    val customerId: String,
    val invoiceTransactionId: String,
    val invoiceNumber: String,
    val remainingAmount: Amount,
    val accountId: String? = null,
    val amount: String = "",
    val paidOn: java.util.Date = java.util.Date(),
    val method: String = "Cash / نقداً",
    val notes: String = "",
    val error: Boolean = false,
    val isSaving: Boolean = false,
)

data class CustomerDraft(
    val id: String? = null,
    val name: String = "",
    val phone: String = "",
    val address: String = "",
    val notes: String = "",
    val creditLimit: String = "",
    val error: Boolean = false,
    val isSaving: Boolean = false,
    val existingCustomer: Customer? = null,
)
