package com.naveenapps.expensemanager.feature.transaction.customer

import androidx.compose.runtime.Stable
import com.naveenapps.expensemanager.core.model.Amount
import com.naveenapps.expensemanager.core.model.CustomerPayment
import com.naveenapps.expensemanager.core.model.TransactionUiItem

@Stable
data class CustomerLedgerState(
    val searchQuery: String = "",
    val allCustomers: List<CustomerLedgerItem> = emptyList(),
    val filteredCustomers: List<CustomerLedgerItem> = emptyList(),
    val expandedCustomerId: String? = null,
    val paymentDraft: CustomerPaymentDraft? = null,
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
    val customerId: String,
    val invoiceTransactionId: String,
    val invoiceNumber: String,
    val remainingAmount: Amount,
    val amount: String = "",
    val error: Boolean = false,
)
