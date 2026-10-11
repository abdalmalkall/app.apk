package com.naveenapps.expensemanager.feature.transaction.supplier

import androidx.compose.runtime.Stable
import com.naveenapps.expensemanager.core.model.Account
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.Supplier
import com.naveenapps.expensemanager.core.model.SupplierPayment
import com.naveenapps.expensemanager.core.model.TransactionUiItem

@Stable
data class SupplierLedgerState(
    val searchQuery: String = "",
    val debtFilter: com.naveenapps.expensemanager.feature.transaction.ledger.DebtFilter = com.naveenapps.expensemanager.feature.transaction.ledger.DebtFilter.ALL,
    val sort: com.naveenapps.expensemanager.feature.transaction.ledger.LedgerSort = com.naveenapps.expensemanager.feature.transaction.ledger.LedgerSort.LAST_ACTIVITY,
    val isLoading: Boolean = true,
    val message: Int? = null,
    val totalOutstanding: String = "",
    val statement: com.naveenapps.expensemanager.core.model.AccountStatement? = null,
    val statementMovements: List<com.naveenapps.expensemanager.core.model.StatementMovement> = emptyList(),
    val allSuppliers: List<SupplierLedgerItem> = emptyList(),
    val filteredSuppliers: List<SupplierLedgerItem> = emptyList(),
    val expandedSupplierId: String? = null,
    val supplierDraft: SupplierDraft? = null,
    val paymentDraft: SupplierPaymentDraft? = null,
    val accounts: List<Account> = emptyList(),
    val recentPurchases: List<TransactionUiItem> = emptyList(),
)

data class SupplierLedgerItem(
    val supplier: Supplier,
    val totalPurchases: Amount,
    val totalPaid: Amount,
    val outstanding: Amount,
    val invoices: List<SupplierInvoiceItem>,
    val lastActivity: Long = 0L,
    val lastPurchase: Long = 0L,
    val movements: List<com.naveenapps.expensemanager.core.model.StatementMovement> = emptyList(),
)

data class SupplierInvoiceItem(
    val transaction: TransactionUiItem,
    val paid: Amount,
    val remaining: Amount,
    val payments: List<SupplierPayment>,
)

data class SupplierDraft(
    val id: String? = null,
    val name: String = "",
    val phone: String = "",
    val address: String = "",
    val companyName: String = "",
    val notes: String = "",
    val error: Boolean = false,
    val isSaving: Boolean = false,
    val existing: Supplier? = null,
)

data class SupplierPaymentDraft(
    val paymentId: String,
    val supplierId: String,
    val invoiceTransactionId: String,
    val remaining: Amount,
    val amount: String = "",
    val paidOn: java.util.Date = java.util.Date(),
    val method: String = "Cash / نقداً",
    val notes: String = "",
    val accountId: String? = null,
    val error: Boolean = false,
    val isSaving: Boolean = false,
)